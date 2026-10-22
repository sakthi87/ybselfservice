#!/usr/bin/env python3
"""Read YugabyteDB system catalogs and write catalog INSERT statements.

The connection file lists one entry per universe. Passwords stay in the
environment and are named by passwordEnv. The script prints SQL. It does not
write to the catalog database.

    YB_AWS_DEV_PASSWORD=secret python3 scripts/extract_catalog.py config/universes.example.json > catalog-load.sql

Review the SQL, then apply it to yugabyte_metadata. Running it again updates
the same database, schema, table, column, and index rows.
"""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

CLOUDS = {"AWS", "AZURE"}
ENVIRONMENTS = {"DEV", "IT", "UAT", "PROD"}
SYSTEM_SCHEMAS = {"pg_catalog", "information_schema", "pg_toast"}

TABLES_SQL = r"""
SELECT coalesce(json_agg(row_to_json(extracted)), '[]'::json)::text
FROM (
    SELECT n.nspname AS schema_name,
           c.relname AS table_name,
           pg_get_userbyid(c.relowner) AS owner_name,
           CASE WHEN c.reltuples < 0 THEN NULL ELSE c.reltuples::bigint END AS row_count,
           pg_total_relation_size(c.oid) AS size_bytes,
           c.relispartition AS is_child_partition,
           obj_description(c.oid, 'pg_class') AS comment,
           (
               SELECT coalesce(json_agg(json_build_object(
                   'name', a.attname,
                   'position', a.attnum,
                   'type', format_type(a.atttypid, a.atttypmod),
                   'nullable', NOT a.attnotnull,
                   'default', pg_get_expr(ad.adbin, ad.adrelid),
                   'comment', col_description(a.attrelid, a.attnum),
                   'identity', a.attidentity,
                   'generated', a.attgenerated
               ) ORDER BY a.attnum), '[]'::json)
               FROM pg_attribute a
               LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
               WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
           ) AS columns,
           (
               SELECT coalesce(json_agg(json_build_object(
                   'name', ic.relname,
                   'unique', i.indisunique,
                   'primary', i.indisprimary,
                   'definition', pg_get_indexdef(i.indexrelid),
                   'predicate', pg_get_expr(i.indpred, i.indrelid),
                   'key_count', i.indnkeyatts,
                   'columns', (
                       SELECT coalesce(json_agg(json_build_object(
                           'name', CASE WHEN k.attnum > 0 THEN a.attname ELSE NULL END,
                           'expression', CASE WHEN k.attnum = 0 THEN pg_get_indexdef(i.indexrelid) ELSE NULL END,
                           'position', k.ord,
                           'direction', CASE WHEN i.indoption[k.ord - 1] & 1 = 1 THEN 'DESC' ELSE 'ASC' END,
                           'nulls', CASE WHEN i.indoption[k.ord - 1] & 2 = 2 THEN 'FIRST' ELSE 'LAST' END,
                           'included', k.ord > i.indnkeyatts
                       ) ORDER BY k.ord), '[]'::json)
                       FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                       LEFT JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum AND k.attnum > 0
                   )
               )), '[]'::json)
               FROM pg_index i
               JOIN pg_class ic ON ic.oid = i.indexrelid
               WHERE i.indrelid = c.oid
           ) AS indexes
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind IN ('r', 'p')
      AND n.nspname <> ALL (%s)
      AND n.nspname NOT LIKE 'pg\_%%'
    ORDER BY n.nspname, c.relname
) extracted
"""

SERVERS_SQL = """
SELECT coalesce(json_agg(row_to_json(nodes)), '[]'::json)::text
FROM (
    SELECT host, port, node_type, cloud, region, zone, public_ip
    FROM yb_servers()
    ORDER BY host
) nodes
"""


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: extract_catalog.py <connection-file.json>")
    config = json.loads(Path(sys.argv[1]).read_text())
    created_by = required(config, "createdBy")
    owner = config.get("owner") or {}
    skip_databases = set(config.get("skipDatabases") or [])
    skip_schemas = SYSTEM_SCHEMAS | set(config.get("skipSchemas") or [])
    include_nodes = config.get("includeNodes", True)
    universes = config.get("universes") or []
    if not universes:
        raise SystemExit("The connection file has no universes")

    lines = [
        "-- Generated from YugabyteDB system catalogs.",
        "-- Review, then apply to the yugabyte_metadata database.",
        "SET search_path TO catalog, public;",
        "",
        owner_sql(owner, created_by),
        "",
    ]
    for universe in universes:
        lines.extend(extract_universe(universe, created_by, owner, skip_databases, skip_schemas, include_nodes))
    sys.stdout.write("\n".join(lines).rstrip() + "\n")


def extract_universe(universe, created_by, owner, skip_databases, skip_schemas, include_nodes):
    cloud = required(universe, "cloud").upper()
    environment = required(universe, "environment").upper()
    code = required(universe, "universeCode")
    if cloud not in CLOUDS or environment not in ENVIRONMENTS:
        raise SystemExit(f"{code}: cloud must be AWS or AZURE and environment must be DEV, IT, UAT, or PROD")
    header = f"-- {cloud} {environment} {code}"
    output = [header]
    if include_nodes:
        output.extend(node_sql(universe, cloud, environment, code, created_by))
    include = set(universe.get("includeDatabases") or [])
    for database in list_databases(universe):
        if database in skip_databases or (include and database not in include):
            continue
        tables = query_json(universe, database, TABLES_SQL % schema_array(skip_schemas))
        if not tables:
            continue
        output.append(f"-- database {database}")
        output.append(database_sql(cloud, environment, code, database, database_owner(universe, database), created_by))
        schemas = sorted({table["schema_name"] for table in tables})
        for schema in schemas:
            output.append(schema_sql(cloud, environment, code, database, schema, created_by))
        for table in tables:
            output.extend(table_sql(cloud, environment, code, database, table, owner, created_by))
        output.append("")
    return output


def node_sql(universe, cloud, environment, code, created_by):
    try:
        nodes = query_json(universe, universe.get("database") or "yugabyte", SERVERS_SQL)
    except RuntimeError as error:
        return [f"-- nodes were not read: {error}"]
    statements = []
    seen_regions = set()
    for node in nodes:
        region = node.get("region") or "unknown"
        zone = node.get("zone") or ""
        key = (region, zone)
        if key not in seen_regions:
            seen_regions.add(key)
            statements.append(region_sql(cloud, environment, code, region, zone, created_by))
        statements.append(one_node_sql(cloud, environment, code, region, zone, node, created_by))
    return statements


def list_databases(universe):
    sql = "SELECT datname FROM pg_database WHERE datallowconn AND NOT datistemplate ORDER BY 1"
    text = psql(universe, universe.get("database") or "yugabyte", sql)
    return [line.strip() for line in text.splitlines() if line.strip()]


def query_json(universe, database, sql):
    text = psql(universe, database, sql).strip()
    if not text:
        return []
    return json.loads(text)


def psql(universe, database, sql):
    password = universe.get("password")
    if not password:
        env_name = required(universe, "passwordEnv")
        password = os.environ.get(env_name)
        if not password:
            raise SystemExit(f"Set {env_name} for {universe.get('universeCode')}")
    command = [
        os.environ.get("PSQL", "/opt/homebrew/opt/libpq/bin/psql"),
        "-h", str(required(universe, "host")),
        "-p", str(universe.get("port") or 5433),
        "-U", str(universe.get("user") or "yugabyte"),
        "-d", database,
        "-v", "ON_ERROR_STOP=1",
        "-t", "-A",
        "-c", sql,
    ]
    env = os.environ.copy()
    env["PGPASSWORD"] = password
    env["PGSSLMODE"] = universe.get("sslMode") or "prefer"
    result = subprocess.run(command, text=True, capture_output=True, env=env)
    if result.returncode != 0:
        message = (result.stderr or result.stdout).strip()
        raise RuntimeError(message or f"psql failed for {database}")
    return result.stdout


def owner_sql(owner, created_by):
    owner_id = required(owner, "id")
    email = required(owner, "contactEmail")
    return f"""
INSERT INTO catalog.data_owner (
    data_owner_id, owner_name, primary_contact_name, primary_contact_email, platform_user_id, created_by, updated_by)
VALUES (
    {q(owner_id)}, {q(required(owner, "name"))}, {q(required(owner, "contactName"))}, {q(email)},
    {q(owner.get("platformUserId") or owner_id)}, {q(created_by)}, {q(created_by)})
ON CONFLICT (data_owner_id) DO UPDATE SET
    owner_name = EXCLUDED.owner_name,
    primary_contact_name = EXCLUDED.primary_contact_name,
    primary_contact_email = EXCLUDED.primary_contact_email,
    platform_user_id = EXCLUDED.platform_user_id,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
""".strip()


def database_owner(universe, database):
    sql = f"SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = {q(database)}"
    return psql(universe, universe.get("database") or "yugabyte", sql).strip() or None


def database_sql(cloud, environment, code, database, owner_name, created_by):
    return f"""
INSERT INTO catalog.database_metadata (
    universe_id, database_name, database_owner, definition_sql, properties, created_by, updated_by)
SELECT universe_id, {q(database)}, {q(owner_name)}, {q("CREATE DATABASE " + database)},
       '{{\"source\":\"yugabyte-system-catalog\"}}'::jsonb, {q(created_by)}, {q(created_by)}
FROM {universe_select(cloud, environment, code)}
ON CONFLICT (universe_id, database_name) DO UPDATE SET
    definition_sql = EXCLUDED.definition_sql,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
""".strip()


def schema_sql(cloud, environment, code, database, schema, created_by):
    return f"""
INSERT INTO catalog.schema_metadata (
    database_id, schema_name, definition_sql, properties, created_by, updated_by)
SELECT database_id, {q(schema)}, {q("CREATE SCHEMA " + schema)},
       '{{\"source\":\"yugabyte-system-catalog\"}}'::jsonb, {q(created_by)}, {q(created_by)}
FROM catalog.database_metadata
WHERE universe_id = ({universe_id(cloud, environment, code)})
  AND database_name = {q(database)}
ON CONFLICT (database_id, schema_name) DO UPDATE SET
    definition_sql = EXCLUDED.definition_sql,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
""".strip()


def table_sql(cloud, environment, code, database, table, owner, created_by):
    schema = table["schema_name"]
    name = table["table_name"]
    indexes = table.get("indexes") or []
    partitioned, strategy, partition = partition_info(indexes)
    properties = json.dumps({"classification": "INTERNAL", "source": "yugabyte-system-catalog"}, separators=(",", ":"))
    table_type = "PARTITION" if table.get("is_child_partition") else "BASE_TABLE"
    lookup = table_id(cloud, environment, code, database, schema, name)
    statements = [f"""
INSERT INTO catalog.table_metadata (
    schema_id, data_owner_id, table_name, table_type, is_partitioned, partition_strategy, partition_definition,
    row_count_estimate, size_bytes, definition_sql, properties, source_system, created_by, updated_by)
SELECT schema_id, {q(required(owner, "id"))}, {q(name)}, {q(table_type)}, {boolean(partitioned)},
       {q(strategy)}, {q(partition)}, {number(table.get("row_count"))}, {number(table.get("size_bytes"))},
       {q(create_table_sql(schema, name, table))}, {q(properties)}::jsonb, 'yugabyte-system-catalog',
       {q(created_by)}, {q(created_by)}
FROM catalog.schema_metadata
WHERE database_id = ({database_id(cloud, environment, code, database)})
  AND schema_name = {q(schema)}
ON CONFLICT (schema_id, table_name) DO UPDATE SET
    data_owner_id = EXCLUDED.data_owner_id,
    table_type = EXCLUDED.table_type,
    is_partitioned = EXCLUDED.is_partitioned,
    partition_strategy = EXCLUDED.partition_strategy,
    partition_definition = EXCLUDED.partition_definition,
    row_count_estimate = EXCLUDED.row_count_estimate,
    size_bytes = EXCLUDED.size_bytes,
    definition_sql = EXCLUDED.definition_sql,
    properties = EXCLUDED.properties,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
""".strip()]
    statements.append(f"DELETE FROM catalog.index_metadata WHERE table_id = ({lookup});")
    statements.append(f"DELETE FROM catalog.column_metadata WHERE table_id = ({lookup});")
    for column in table.get("columns") or []:
        identity = column.get("identity") or ""
        generated = column.get("generated") or ""
        identity_generation = {"a": "ALWAYS", "d": "BY DEFAULT"}.get(identity)
        statements.append(f"""
INSERT INTO catalog.column_metadata (
    table_id, column_name, ordinal_position, data_type, is_nullable, is_identity, identity_generation,
    is_generated, default_expression, comment, created_by, updated_by)
SELECT table_id, {q(column["name"])}, {int(column["position"])}, {q(str(column["type"])[:255])},
       {boolean(column.get("nullable", True))}, {boolean(identity in ("a", "d"))}, {q(identity_generation)},
       {boolean(generated == "s")}, {q(column.get("default"))}, {q(column.get("comment"))},
       {q(created_by)}, {q(created_by)}
FROM catalog.table_metadata
WHERE table_id = ({lookup});
""".strip())
    for index in indexes:
        kind = index_kind(index)
        predicate = index.get("predicate")
        partial = bool(predicate)
        statements.append(f"""
INSERT INTO catalog.index_metadata (
    table_id, index_name, index_kind, index_method, is_unique, is_primary, is_partial, predicate,
    index_definition, properties, created_by, updated_by)
SELECT table_id, {q(index["name"])}, {q(kind)}::catalog.index_kind, 'LSM',
       {boolean(index.get("unique") or index.get("primary"))}, {boolean(index.get("primary"))},
       {boolean(partial)}, {q(predicate)}, {q(index.get("definition") or index["name"])},
       '{{}}'::jsonb, {q(created_by)}, {q(created_by)}
FROM catalog.table_metadata
WHERE table_id = ({lookup});
""".strip())
        for column in index.get("columns") or []:
            if not column.get("name") and not column.get("expression"):
                continue
            statements.append(f"""
INSERT INTO catalog.index_column_metadata (
    index_id, ordinal_position, column_name, expression, sort_direction, nulls_position, is_included_column,
    created_by, updated_by)
SELECT index_id, {int(column["position"])}, {q(column.get("name"))}, {q(None if column.get("name") else column.get("expression"))},
       {q(column.get("direction") or "ASC")}, {q(column.get("nulls") or "LAST")}, {boolean(column.get("included"))},
       {q(created_by)}, {q(created_by)}
FROM catalog.index_metadata
WHERE table_id = ({lookup}) AND index_name = {q(index["name"])};
""".strip())
    return statements


def region_sql(cloud, environment, code, region, zone, created_by):
    zone_sql = q(zone) if zone else "NULL"
    return f"""
INSERT INTO catalog.universe_region (
    universe_id, location_type, cloud_provider, region_code, region_name, zone_name, node_count,
    is_primary_region, created_by, updated_by)
SELECT universe_id, 'CLOUD'::catalog.location_type, {q(cloud)}::catalog.cloud_provider,
       {q(region)}, {q(region)}, {zone_sql}, 0, FALSE, {q(created_by)}, {q(created_by)}
FROM {universe_select(cloud, environment, code)}
ON CONFLICT (universe_id, region_code, zone_name) DO UPDATE SET
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
""".strip()


def one_node_sql(cloud, environment, code, region, zone, node, created_by):
    host = node.get("host") or "unknown"
    role = "MASTER_TSERVER" if (node.get("node_type") or "").lower() == "primary" else "TSERVER"
    address = node.get("public_ip") if is_inet(node.get("public_ip")) else None
    zone_match = "zone_name IS NULL" if not zone else f"zone_name = {q(zone)}"
    return f"""
INSERT INTO catalog.universe_node (
    universe_id, universe_region_id, node_name, host_name, public_ip, node_role, status,
    yb_master_port, yb_tserver_port, created_by, updated_by)
SELECT u.universe_id, r.universe_region_id, {q(host)}, {q(host)}, {q(address)}::inet,
       {q(role)}::catalog.node_role, 'ACTIVE'::catalog.node_status, 7100, 9000, {q(created_by)}, {q(created_by)}
FROM catalog.universe u
JOIN catalog.environment e ON e.environment_id = u.environment_id
JOIN catalog.cloud c ON c.cloud_id = e.cloud_id
JOIN catalog.universe_region r ON r.universe_id = u.universe_id AND r.region_code = {q(region)} AND {zone_match}
WHERE c.cloud_code::text = {q(cloud)}
  AND e.environment_code::text = {q(environment)}
  AND u.universe_code = {q(code)}
ON CONFLICT (universe_id, node_name) DO UPDATE SET
    host_name = EXCLUDED.host_name,
    public_ip = EXCLUDED.public_ip,
    node_role = EXCLUDED.node_role,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;
""".strip()


def partition_info(indexes):
    for index in indexes:
        if not index.get("primary"):
            continue
        columns = hash_columns(index.get("definition") or "")
        if columns:
            return True, "HASH", "HASH (" + ", ".join(columns) + ")"
    return False, None, None


def hash_columns(definition):
    start = definition.rfind("(")
    end = definition.rfind(")")
    if start < 0 or end < start:
        return []
    columns = []
    for part in split_csv(definition[start + 1:end]):
        if re.search(r"\bHASH\b", part, re.IGNORECASE):
            token = part.replace('"', "").strip().split()
            if token:
                columns.append(token[0])
    return columns


def create_table_sql(schema, name, table):
    lines = [f"CREATE TABLE {ident(schema)}.{ident(name)} ("]
    body = []
    for column in table.get("columns") or []:
        nullability = "" if column.get("nullable", True) else " NOT NULL"
        default = f" DEFAULT {column['default']}" if column.get("default") else ""
        body.append(f"    {ident(column['name'])} {column['type']}{nullability}{default}")
    lines.append(",\n".join(body))
    lines.append(");")
    if table.get("comment"):
        lines.append(f"COMMENT ON TABLE {ident(schema)}.{ident(name)} IS {q(table['comment'])};")
    return "\n".join(lines)


def index_kind(index):
    if index.get("primary"):
        return "PRIMARY_KEY"
    if index.get("predicate"):
        return "PARTIAL"
    if index.get("unique"):
        return "UNIQUE"
    return "NON_UNIQUE"


def universe_select(cloud, environment, code):
    return f"""(
    SELECT u.universe_id
    FROM catalog.universe u
    JOIN catalog.environment e ON e.environment_id = u.environment_id
    JOIN catalog.cloud c ON c.cloud_id = e.cloud_id
    WHERE c.cloud_code::text = {q(cloud)}
      AND e.environment_code::text = {q(environment)}
      AND u.universe_code = {q(code)}
) AS universe_source"""


def universe_id(cloud, environment, code):
    return f"SELECT universe_id FROM {universe_select(cloud, environment, code)}"


def database_id(cloud, environment, code, database):
    return f"""
SELECT database_id FROM catalog.database_metadata
WHERE universe_id = ({universe_id(cloud, environment, code)}) AND database_name = {q(database)}
""".strip()


def table_id(cloud, environment, code, database, schema, table):
    return f"""
SELECT t.table_id
FROM catalog.table_metadata t
JOIN catalog.schema_metadata s ON s.schema_id = t.schema_id
WHERE s.database_id = ({database_id(cloud, environment, code, database)})
  AND s.schema_name = {q(schema)}
  AND t.table_name = {q(table)}
""".strip()


def schema_array(schemas):
    return "ARRAY[" + ", ".join(q(schema) for schema in sorted(schemas)) + "]::text[]"


def ident(name):
    if re.match(r"^[a-z_][a-z0-9_]*$", name or ""):
        return name
    return '"' + str(name).replace('"', '""') + '"'


def q(value):
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def boolean(value):
    return "TRUE" if value else "FALSE"


def number(value):
    if value is None:
        return "NULL"
    return str(int(value))


def required(values, key):
    value = values.get(key)
    if value is None or str(value).strip() == "":
        raise SystemExit(f"Connection file is missing {key}")
    return str(value).strip()


def split_csv(value):
    parts = []
    current = []
    depth = 0
    for character in value:
        if character == "(":
            depth += 1
        elif character == ")":
            depth = max(0, depth - 1)
        if character == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(character)
    if current:
        parts.append("".join(current))
    return parts


def is_inet(value):
    if not value:
        return False
    ipv4 = re.match(r"^(\d{1,3}\.){3}\d{1,3}$", value)
    ipv6 = ":" in value and re.match(r"^[0-9a-fA-F:]+$", value)
    return bool(ipv4 or ipv6)


if __name__ == "__main__":
    main()
