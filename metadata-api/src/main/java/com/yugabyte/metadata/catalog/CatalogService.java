package com.yugabyte.metadata.catalog;

import tools.jackson.databind.ObjectMapper;
import com.yugabyte.metadata.web.NotFoundException;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogService {
  private static final Pattern UUID_RE = Pattern.compile(
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
      Pattern.CASE_INSENSITIVE);

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CatalogService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public Map<String, Object> health() {
    Map<String, Object> row = one(
        "SELECT current_database() AS database, version() AS version");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "ok");
    body.put("database", row.get("database"));
    body.put("version", row.get("version"));
    return body;
  }

  public List<Map<String, Object>> clouds() {
    return rows("""
        SELECT cloud_id, cloud_code, cloud_name, status
          FROM catalog.v_clouds
         ORDER BY cloud_code
        """).stream().map(this::cloud).toList();
  }

  public List<Map<String, Object>> environments(String cloudCode) {
    return rows("""
        SELECT e.environment_id, e.environment_code, e.environment_name, e.status,
               c.cloud_code, c.cloud_name
          FROM catalog.environment e
          JOIN catalog.cloud c ON c.cloud_id = e.cloud_id
         WHERE c.cloud_code::text = ?
           AND e.status = 'ACTIVE'
         ORDER BY e.environment_code
        """, cloudCode.toUpperCase()).stream().map(this::environment).toList();
  }

  public List<Map<String, Object>> universesForCloud(String cloudCode, String environment) {
    SqlFilter filter = new SqlFilter();
    filter.eq("e.cloud_code", cloudCode.toUpperCase());
    filter.eq("e.environment_code", upper(environment));
    return rows("""
        SELECT *
          FROM catalog.v_universe_summary e
        """ + filter.where() + """
         ORDER BY e.environment_code, e.universe_code
        """, filter.args()).stream().map(this::universeSummary).toList();
  }

  public List<Map<String, Object>> listUniverses(String cloud, String environment) {
    SqlFilter filter = new SqlFilter();
    filter.eq("cloud_code", upper(cloud));
    filter.eq("environment_code", upper(environment));
    return rows("""
        SELECT *
          FROM catalog.v_universe_summary
        """ + filter.where() + """
         ORDER BY cloud_code, environment_code, universe_code
        """, filter.args()).stream().map(this::universeSummary).toList();
  }

  public List<Map<String, Object>> universesForEnvironment(String environment, String cloud) {
    SqlFilter filter = new SqlFilter();
    filter.eq("environment_code", environment.toUpperCase());
    filter.eq("cloud_code", upper(cloud));
    return rows("""
        SELECT *
          FROM catalog.v_universe_summary
        """ + filter.where() + """
         ORDER BY cloud_code, universe_code
        """, filter.args()).stream().map(this::universeSummary).toList();
  }

  public Map<String, Object> universe(String idOrCode) {
    UUID universeId = findUniverse(idOrCode);
    Map<String, Object> universe = one("""
        SELECT u.*, c.cloud_code, e.environment_code
          FROM catalog.universe u
          JOIN catalog.environment e ON e.environment_id = u.environment_id
          JOIN catalog.cloud c ON c.cloud_id = e.cloud_id
         WHERE u.universe_id = ?
        """, universeId);
    if (universe == null) {
      throw new NotFoundException("Universe " + idOrCode + " was not found");
    }
    List<Map<String, Object>> regions = rows("""
        SELECT *
          FROM catalog.universe_region
         WHERE universe_id = ?
         ORDER BY leader_preference NULLS LAST, region_code
        """, universeId).stream().map(this::region).toList();
    List<Map<String, Object>> nodes = rows("""
        SELECT *
          FROM catalog.v_node_catalog
         WHERE universe_id = ?
         ORDER BY node_name
        """, universeId).stream().map(this::node).toList();
    Map<String, Object> capacity = one("""
        SELECT count(*)::int AS total_nodes,
               coalesce(sum(cpu_cores), 0) AS total_cpu,
               coalesce(sum(memory_gib), 0) AS total_memory,
               coalesce(sum(storage_total_gib), 0) AS total_storage,
               coalesce(sum(storage_used_gib), 0) AS used_storage
          FROM catalog.universe_node
         WHERE universe_id = ?
        """, universeId);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("universeId", text(universe.get("universe_id")));
    body.put("universeCode", universe.get("universe_code"));
    body.put("universeName", universe.get("universe_name"));
    body.put("cloud", universe.get("cloud_code"));
    body.put("environment", universe.get("environment_code"));
    body.put("status", universe.get("status"));
    body.put("topology", universe.get("topology"));
    body.put("yugabyteVersion", universe.get("yugabyte_version"));
    body.put("replicationFactor", number(universe.get("replication_factor")));
    body.put("faultTolerance", universe.get("fault_tolerance_level"));
    body.put("apiEndpoint", universe.get("api_endpoint"));
    body.put("masterCount", number(universe.get("master_count")));
    body.put("tserverCount", number(universe.get("tserver_count")));
    body.put("regions", regions);
    body.put("capacity", capacity(capacity));
    body.put("nodes", nodes);
    body.put("configuration", universe.get("configuration"));
    body.put("metadata", Map.of(
        "version", number(universe.get("metadata_version")),
        "sourceSystem", universe.get("source_system"),
        "updatedAt", universe.get("updated_at")));
    return body;
  }

  public List<Map<String, Object>> regions(String idOrCode) {
    UUID universeId = findUniverse(idOrCode);
    return rows("""
        SELECT *
          FROM catalog.universe_region
         WHERE universe_id = ?
         ORDER BY leader_preference NULLS LAST, region_code
        """, universeId).stream().map(this::region).toList();
  }

  public List<Map<String, Object>> universeNodes(String idOrCode) {
    UUID universeId = findUniverse(idOrCode);
    return rows("""
        SELECT *
          FROM catalog.v_node_catalog
         WHERE universe_id = ?
         ORDER BY node_name
        """, universeId).stream().map(this::node).toList();
  }

  public List<Map<String, Object>> databases(String idOrCode) {
    UUID universeId = findUniverse(idOrCode);
    return rows("""
        SELECT database_id, database_name, status, database_owner, metadata_version, updated_at
          FROM catalog.database_metadata
         WHERE universe_id = ?
           AND status <> 'RETIRED'
         ORDER BY database_name
        """, universeId).stream().map(row -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("databaseId", text(row.get("database_id")));
      item.put("databaseName", row.get("database_name"));
      item.put("status", row.get("status"));
      item.put("databaseOwner", row.get("database_owner"));
      item.put("metadataVersion", number(row.get("metadata_version")));
      item.put("updatedAt", row.get("updated_at"));
      return item;
    }).toList();
  }

  public List<Map<String, Object>> schemas(String universe, String database) {
    return schemas(findDatabase(universe, database).toString());
  }

  public List<Map<String, Object>> tables(String universe, String database, String schema) {
    return schemaTables(findSchema(universe, database, schema).toString());
  }

  public Map<String, Object> table(String universe, String database, String schema, String table) {
    return simpleTable(loadTable(findTable(universe, database, schema, table).toString()));
  }

  public Map<String, Object> tableDefinition(String universe, String database, String schema, String table) {
    return definition(loadTable(findTable(universe, database, schema, table).toString()));
  }

  public List<Map<String, Object>> schemas(String databaseId) {
    UUID id = uuidOrNotFound(databaseId, "Database");
    List<Map<String, Object>> found = rows("""
        SELECT schema_id, schema_name, status, schema_owner, metadata_version, updated_at
          FROM catalog.schema_metadata
         WHERE database_id = ?
           AND status <> 'RETIRED'
         ORDER BY schema_name
        """, id);
    if (found.isEmpty() && one("SELECT 1 AS present FROM catalog.database_metadata WHERE database_id = ?", id) == null) {
      throw new NotFoundException("Database " + databaseId + " was not found");
    }
    return found.stream().map(row -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("schemaId", text(row.get("schema_id")));
      item.put("schemaName", row.get("schema_name"));
      item.put("status", row.get("status"));
      item.put("schemaOwner", row.get("schema_owner"));
      item.put("metadataVersion", number(row.get("metadata_version")));
      item.put("updatedAt", row.get("updated_at"));
      return item;
    }).toList();
  }

  public List<Map<String, Object>> schemaTables(String schemaId) {
    UUID id = uuidOrNotFound(schemaId, "Schema");
    List<Map<String, Object>> found = rows("""
        SELECT *
          FROM catalog.v_table_catalog
         WHERE schema_id = ?
         ORDER BY table_name
        """, id);
    if (found.isEmpty() && one("SELECT 1 AS present FROM catalog.schema_metadata WHERE schema_id = ?", id) == null) {
      throw new NotFoundException("Schema " + schemaId + " was not found");
    }
    return found.stream().map(this::tableSummary).toList();
  }

  public Map<String, Object> searchTables(String pageSize, String pageToken, String page, Map<String, String> filters) {
    Pages.Window window = Pages.window(pageSize, pageToken, page, 25, mapper);
    SqlFilter filter = tableFilter(filters);
    int total = count("SELECT count(*)::int AS total FROM catalog.v_table_catalog" + filter.where(), filter.args());
    List<Object> args = new ArrayList<>(filter.params);
    args.add(window.pageSize());
    args.add(window.offset());
    List<Map<String, Object>> items = rows("""
        SELECT *
          FROM catalog.v_table_catalog
        """ + filter.where() + """
         ORDER BY cloud_code, environment_code, universe_code, database_name, schema_name, table_name
         LIMIT ? OFFSET ?
        """, args.toArray()).stream().map(this::tableSummary).toList();
    return Pages.body(items, window.pageSize(), window.offset(), total);
  }

  public Map<String, Object> table(String tableId) {
    return simpleTable(loadTable(tableId));
  }

  public Map<String, Object> tableDefinition(String tableId) {
    return definition(loadTable(tableId));
  }

  public Map<String, Object> searchNodes(String pageSize, String pageToken, String page, Map<String, String> filters) {
    Pages.Window window = Pages.window(pageSize, pageToken, page, 50, mapper);
    SqlFilter filter = nodeFilter(filters);
    int total = count("SELECT count(*)::int AS total FROM catalog.v_node_catalog" + filter.where(), filter.args());
    List<Object> args = new ArrayList<>(filter.params);
    args.add(window.pageSize());
    args.add(window.offset());
    List<Map<String, Object>> items = rows("""
        SELECT *
          FROM catalog.v_node_catalog
        """ + filter.where() + """
         ORDER BY cloud_code, environment_code, universe_code, node_name
         LIMIT ? OFFSET ?
        """, args.toArray()).stream().map(this::node).toList();
    Map<String, Object> capacity = one("""
        SELECT count(*)::int AS total_nodes,
               coalesce(sum(cpu_cores), 0) AS total_cpu,
               coalesce(sum(memory_gib), 0) AS total_memory,
               coalesce(sum(storage_total_gib), 0) AS total_storage,
               coalesce(sum(storage_used_gib), 0) AS used_storage
          FROM catalog.v_node_catalog
        """ + filter.where(), filter.args());
    Map<String, Object> body = Pages.body(items, window.pageSize(), window.offset(), total);
    body.put("capacity", capacity(capacity));
    return body;
  }

  public Map<String, Object> node(String nodeId) {
    UUID id = uuidOrNotFound(nodeId, "Node");
    Map<String, Object> row = one("SELECT * FROM catalog.v_node_catalog WHERE node_id = ?", id);
    if (row == null) {
      throw new NotFoundException("Node " + nodeId + " was not found");
    }
    return node(row);
  }

  public List<Map<String, Object>> owners() {
    return rows("""
        SELECT data_owner_id, owner_name, status,
               primary_contact_name, primary_contact_email,
               secondary_contact_name, secondary_contact_email
          FROM catalog.data_owner
         ORDER BY data_owner_id
        """).stream().map(row -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("dataOwnerId", row.get("data_owner_id"));
      item.put("ownerName", row.get("owner_name"));
      item.put("status", row.get("status"));
      item.put("primaryContact", contact(row.get("primary_contact_name"), row.get("primary_contact_email")));
      item.put("secondaryContact", row.get("secondary_contact_name") == null
          ? null
          : contact(row.get("secondary_contact_name"), row.get("secondary_contact_email")));
      return item;
    }).toList();
  }

  public Map<String, Object> options() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("clouds", rows("""
        SELECT cloud_code, cloud_name FROM catalog.v_clouds ORDER BY cloud_code
        """).stream().map(row -> Map.of("code", row.get("cloud_code"), "name", row.get("cloud_name"))).toList());
    body.put("environments", List.of("DEV", "IT", "UAT", "PROD"));
    body.put("universes", rows("""
        SELECT universe_code, universe_name, cloud_code, environment_code
          FROM catalog.v_universe_summary
         ORDER BY cloud_code, environment_code, universe_code
        """).stream().map(row -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("code", row.get("universe_code"));
      item.put("name", row.get("universe_name"));
      item.put("cloud", row.get("cloud_code"));
      item.put("environment", row.get("environment_code"));
      return item;
    }).toList());
    body.put("owners", rows("""
        SELECT data_owner_id, owner_name
          FROM catalog.data_owner
         WHERE status = 'ACTIVE'
         ORDER BY data_owner_id
        """).stream().map(row -> Map.of("id", row.get("data_owner_id"), "name", row.get("owner_name"))).toList());
    body.put("regions", rows("""
        SELECT DISTINCT region_code, region_name
          FROM catalog.universe_region
         ORDER BY region_code
        """).stream().map(row -> Map.of("code", row.get("region_code"), "name", row.get("region_name"))).toList());
    return body;
  }

  private TableBundle loadTable(String tableId) {
    UUID id = uuidOrNotFound(tableId, "Table");
    Map<String, Object> table = one("""
        SELECT
            t.table_id, t.table_name, t.table_type, t.status, t.is_partitioned,
            t.partition_strategy, t.partition_definition, t.tablespace_name,
            t.row_count_estimate, t.size_bytes, t.definition_sql, t.properties, t.updated_at,
            c.cloud_code, e.environment_code, u.universe_code, u.api_endpoint, u.configuration,
            d.database_name, s.schema_name,
            o.data_owner_id, o.owner_name, o.primary_contact_name, o.primary_contact_email,
            o.secondary_contact_name, o.secondary_contact_email, o.platform_user_id
          FROM catalog.table_metadata t
          JOIN catalog.schema_metadata s ON s.schema_id = t.schema_id
          JOIN catalog.database_metadata d ON d.database_id = s.database_id
          JOIN catalog.universe u ON u.universe_id = d.universe_id
          JOIN catalog.environment e ON e.environment_id = u.environment_id
          JOIN catalog.cloud c ON c.cloud_id = e.cloud_id
          JOIN catalog.data_owner o ON o.data_owner_id = t.data_owner_id
         WHERE t.table_id = ?
        """, id);
    if (table == null) {
      throw new NotFoundException("Table " + tableId + " was not found");
    }
    List<Map<String, Object>> columns = rows("""
        SELECT column_name, ordinal_position, data_type, udt_name, is_nullable,
               is_identity, identity_generation, default_expression, generated_expression,
               comment, numeric_precision, numeric_scale
          FROM catalog.column_metadata
         WHERE table_id = ?
           AND status = 'ACTIVE'
         ORDER BY ordinal_position
        """, id);
    List<Map<String, Object>> indexes = rows("""
        SELECT index_id, index_name, index_kind, index_method, status,
               is_unique, is_primary, is_partial, predicate, index_definition
          FROM catalog.index_metadata
         WHERE table_id = ?
           AND status <> 'DROPPED'
         ORDER BY is_primary DESC, index_name
        """, id);
    List<UUID> indexIds = indexes.stream().map(row -> (UUID) raw(row.get("index_id"))).toList();
    List<Map<String, Object>> indexColumns = indexColumns(indexIds);
    return new TableBundle(table, columns, indexes, indexColumns);
  }

  private List<Map<String, Object>> indexColumns(List<UUID> indexIds) {
    if (indexIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query("""
        SELECT index_id, ordinal_position, column_name, expression,
               sort_direction, nulls_position, is_included_column
          FROM catalog.index_column_metadata
         WHERE index_id = ANY(?)
         ORDER BY index_id, ordinal_position
        """, ps -> {
      Array array = ps.getConnection().createArrayOf("uuid", indexIds.toArray());
      ps.setArray(1, array);
    }, (rs, rowNum) -> read(rs));
  }

  private Map<String, Object> simpleTable(TableBundle bundle) {
    Map<String, Object> table = bundle.table();
    Map<String, Object> properties = jsonObject(table.get("properties"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("table", table.get("table_name"));
    body.put("cloud", table.get("cloud_code"));
    body.put("environment", table.get("environment_code"));
    body.put("universe", table.get("universe_code"));
    body.put("database", table.get("database_name"));
    body.put("schema", table.get("schema_name"));
    body.put("owner", owner(table));
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("type", table.get("table_type"));
    info.put("status", table.get("status"));
    info.put("partitioned", table.get("is_partitioned"));
    info.put("rowCount", number(table.get("row_count_estimate")));
    info.put("sizeBytes", number(table.get("size_bytes")));
    body.put("tableInfo", info);
    body.put("columns", bundle.columns().stream().map(column -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", column.get("column_name"));
      item.put("type", column.get("data_type"));
      item.put("nullable", column.get("is_nullable"));
      item.put("primaryKey", primary(bundle, column));
      item.put("description", column.get("comment"));
      return item;
    }).toList());
    body.put("indexes", bundle.indexes().stream().map(index -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", index.get("index_name"));
      item.put("type", index.get("index_kind"));
      item.put("columns", indexColumnNames(bundle, index));
      item.put("unique", index.get("is_unique"));
      return item;
    }).toList());
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("classification", properties.get("classification"));
    metadata.put("businessDomain", properties.get("business_domain"));
    metadata.put("lastUpdated", table.get("updated_at"));
    body.put("metadata", metadata);
    return body;
  }

  private Map<String, Object> definition(TableBundle bundle) {
    Map<String, Object> table = bundle.table();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("table", table.get("table_name"));
    body.put("cloud", table.get("cloud_code"));
    body.put("environment", table.get("environment_code"));
    body.put("universe", table.get("universe_code"));
    body.put("database", table.get("database_name"));
    body.put("schema", table.get("schema_name"));
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("qualifiedName", qualifiedName(table));
    target.put("apiEndpoint", table.get("api_endpoint"));
    target.put("ysql", ysql(table.get("configuration")));
    body.put("target", target);
    body.put("ddl", table.get("definition_sql"));
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("type", table.get("table_type"));
    info.put("status", table.get("status"));
    info.put("tablespace", table.get("tablespace_name"));
    body.put("tableInfo", info);
    Map<String, Object> partitioning = new LinkedHashMap<>();
    partitioning.put("partitioned", table.get("is_partitioned"));
    partitioning.put("strategy", table.get("partition_strategy"));
    partitioning.put("definition", table.get("partition_definition"));
    body.put("partitioning", partitioning);
    body.put("columns", bundle.columns().stream().map(column -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", column.get("column_name"));
      item.put("ordinal", number(column.get("ordinal_position")));
      item.put("type", column.get("data_type"));
      item.put("udtName", column.get("udt_name"));
      item.put("nullable", column.get("is_nullable"));
      item.put("primaryKey", primary(bundle, column));
      item.put("identity", column.get("is_identity"));
      item.put("identityGeneration", column.get("identity_generation"));
      item.put("defaultExpression", column.get("default_expression"));
      item.put("generatedExpression", column.get("generated_expression"));
      item.put("numericPrecision", number(column.get("numeric_precision")));
      item.put("numericScale", number(column.get("numeric_scale")));
      item.put("description", column.get("comment"));
      return item;
    }).toList());
    body.put("indexes", bundle.indexes().stream().map(index -> {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", index.get("index_name"));
      item.put("type", index.get("index_kind"));
      item.put("method", index.get("index_method"));
      item.put("status", index.get("status"));
      item.put("unique", index.get("is_unique"));
      item.put("primary", index.get("is_primary"));
      item.put("partial", index.get("is_partial"));
      item.put("predicate", index.get("predicate"));
      item.put("definition", index.get("index_definition"));
      item.put("columns", bundle.indexColumns().stream()
          .filter(column -> sameId(column.get("index_id"), index.get("index_id")))
          .map(column -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", column.get("column_name"));
            detail.put("expression", column.get("expression"));
            detail.put("position", number(column.get("ordinal_position")));
            detail.put("direction", column.get("sort_direction"));
            detail.put("nulls", column.get("nulls_position"));
            detail.put("included", column.get("is_included_column"));
            return detail;
          }).toList());
      return item;
    }).toList());
    body.put("constraints", bundle.indexes().stream()
        .filter(index -> Boolean.TRUE.equals(index.get("is_primary")) || Boolean.TRUE.equals(index.get("is_unique")))
        .map(index -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("name", index.get("index_name"));
          item.put("type", Boolean.TRUE.equals(index.get("is_primary")) ? "PRIMARY_KEY" : "UNIQUE");
          item.put("columns", indexColumnNames(bundle, index));
          item.put("predicate", index.get("predicate"));
          item.put("definition", index.get("index_definition"));
          return item;
        }).toList());
    return body;
  }

  private Map<String, Object> owner(Map<String, Object> table) {
    Map<String, Object> owner = new LinkedHashMap<>();
    owner.put("id", table.get("data_owner_id"));
    owner.put("name", table.get("owner_name"));
    owner.put("primaryContact", formatContact(table.get("primary_contact_name"), table.get("primary_contact_email")));
    owner.put("secondaryContact", formatContact(table.get("secondary_contact_name"), table.get("secondary_contact_email")));
    return owner;
  }

  private boolean primary(TableBundle bundle, Map<String, Object> column) {
    Object name = column.get("column_name");
    return bundle.indexes().stream().anyMatch(index -> Boolean.TRUE.equals(index.get("is_primary"))
        && bundle.indexColumns().stream().anyMatch(indexColumn ->
            sameId(indexColumn.get("index_id"), index.get("index_id")) && name.equals(indexColumn.get("column_name"))));
  }

  private List<String> indexColumnNames(TableBundle bundle, Map<String, Object> index) {
    return bundle.indexColumns().stream()
        .filter(column -> sameId(column.get("index_id"), index.get("index_id")) && column.get("column_name") != null)
        .map(column -> (String) column.get("column_name"))
        .toList();
  }

  private SqlFilter tableFilter(Map<String, String> query) {
    SqlFilter filter = new SqlFilter();
    filter.eq("cloud_code", upper(query.get("cloud")));
    filter.eq("environment_code", upper(query.get("environment")));
    filter.eq("universe_code", blankToNull(query.get("universe")));
    filter.eq("database_name", blankToNull(query.get("database")));
    filter.eq("schema_name", blankToNull(query.get("schema")));
    filter.eq("data_owner_id", blankToNull(query.get("owner")));
    filter.eqLower("primary_contact_email", blankToNull(query.get("ownerEmail")));
    filter.eqLower("platform_user_id", blankToNull(query.get("ownerUserId")));
    filter.eq("table_name", blankToNull(query.get("table")));
    filter.ilike("table_name", blankToNull(query.get("tableName")));
    String q = blankToNull(query.get("q"));
    if (q != null) {
      filter.params.add("%" + q.trim() + "%");
      int marker = filter.params.size();
      filter.append("""
          (
            table_name ILIKE ?
            OR owner_name ILIKE ?
            OR data_owner_id ILIKE ?
            OR database_name ILIKE ?
            OR schema_name ILIKE ?
          )
          """);
      Object pattern = filter.params.get(marker - 1);
      filter.params.add(pattern);
      filter.params.add(pattern);
      filter.params.add(pattern);
      filter.params.add(pattern);
    }
    filter.eq("status", upper(query.get("status")));
    return filter;
  }

  private SqlFilter nodeFilter(Map<String, String> query) {
    SqlFilter filter = new SqlFilter();
    filter.eq("cloud_code", upper(query.get("cloud")));
    filter.eq("environment_code", upper(query.get("environment")));
    filter.eq("universe_code", blankToNull(query.get("universe")));
    filter.eq("region_code", blankToNull(query.get("region")));
    filter.eq("node_role", upper(query.get("role")));
    filter.eq("status", upper(query.get("status")));
    filter.ilike("node_name", blankToNull(query.get("nodeName")));
    return filter;
  }

  private UUID findDatabase(String universe, String database) {
    UUID universeId = findUniverse(universe);
    Map<String, Object> row = one("""
        SELECT database_id
          FROM catalog.database_metadata
         WHERE universe_id = ?
           AND database_name = ?
           AND status <> 'RETIRED'
        """, universeId, database);
    if (row == null) {
      throw new NotFoundException("Database " + database + " was not found in universe " + universe);
    }
    return (UUID) raw(row.get("database_id"));
  }

  private UUID findSchema(String universe, String database, String schema) {
    UUID databaseId = findDatabase(universe, database);
    Map<String, Object> row = one("""
        SELECT schema_id
          FROM catalog.schema_metadata
         WHERE database_id = ?
           AND schema_name = ?
           AND status <> 'RETIRED'
        """, databaseId, schema);
    if (row == null) {
      throw new NotFoundException("Schema " + schema + " was not found in database " + database);
    }
    return (UUID) raw(row.get("schema_id"));
  }

  private UUID findTable(String universe, String database, String schema, String table) {
    UUID schemaId = findSchema(universe, database, schema);
    Map<String, Object> row = one("""
        SELECT table_id
          FROM catalog.table_metadata
         WHERE schema_id = ?
           AND table_name = ?
           AND status <> 'RETIRED'
        """, schemaId, table);
    if (row == null) {
      throw new NotFoundException("Table " + table + " was not found in schema " + schema);
    }
    return (UUID) raw(row.get("table_id"));
  }

  private UUID findUniverse(String idOrCode) {
    Map<String, Object> row = isUuid(idOrCode)
        ? one("SELECT universe_id FROM catalog.universe WHERE universe_id = ?", UUID.fromString(idOrCode))
        : one("SELECT universe_id FROM catalog.universe WHERE upper(universe_code) = upper(?)", idOrCode);
    if (row == null) {
      throw new NotFoundException("Universe " + idOrCode + " was not found");
    }
    return (UUID) raw(row.get("universe_id"));
  }

  private Map<String, Object> cloud(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("cloudId", text(row.get("cloud_id")));
    item.put("cloudCode", row.get("cloud_code"));
    item.put("cloudName", row.get("cloud_name"));
    item.put("status", row.get("status"));
    return item;
  }

  private Map<String, Object> environment(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("environmentId", text(row.get("environment_id")));
    item.put("environmentCode", row.get("environment_code"));
    item.put("environmentName", row.get("environment_name"));
    item.put("status", row.get("status"));
    item.put("cloudCode", row.get("cloud_code"));
    item.put("cloudName", row.get("cloud_name"));
    return item;
  }

  private Map<String, Object> universeSummary(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("universeId", text(row.get("universe_id")));
    item.put("universeCode", row.get("universe_code"));
    item.put("universeName", row.get("universe_name"));
    item.put("cloud", row.get("cloud_code"));
    item.put("cloudName", row.get("cloud_name"));
    item.put("environment", row.get("environment_code"));
    item.put("environmentName", row.get("environment_name"));
    item.put("status", row.get("status"));
    item.put("topology", row.get("topology"));
    item.put("yugabyteVersion", row.get("yugabyte_version"));
    item.put("replicationFactor", number(row.get("replication_factor")));
    item.put("nodeCount", number(row.get("expected_node_count")));
    item.put("regionCount", number(row.get("region_count")));
    item.put("masterCount", number(row.get("master_count")));
    item.put("tserverCount", number(row.get("tserver_count")));
    item.put("faultToleranceLevel", row.get("fault_tolerance_level"));
    item.put("createdAt", row.get("created_at"));
    item.put("updatedAt", row.get("updated_at"));
    return item;
  }

  private Map<String, Object> region(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("universeRegionId", text(row.get("universe_region_id")));
    item.put("region", row.get("region_code"));
    item.put("regionName", row.get("region_name"));
    item.put("zone", row.get("zone_name"));
    item.put("locationType", row.get("location_type"));
    item.put("cloudProvider", row.get("cloud_provider"));
    item.put("nodeCount", number(row.get("node_count")));
    item.put("leaderPreference", number(row.get("leader_preference")));
    item.put("isPrimaryRegion", row.get("is_primary_region"));
    item.put("status", row.get("status"));
    item.put("placementConfiguration", row.get("placement_configuration"));
    return item;
  }

  private Map<String, Object> node(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("nodeId", text(row.get("node_id")));
    item.put("name", row.get("node_name"));
    item.put("hostName", row.get("host_name"));
    item.put("privateIp", row.get("private_ip"));
    item.put("publicIp", row.get("public_ip"));
    item.put("role", row.get("node_role"));
    item.put("status", row.get("status"));
    item.put("instanceType", row.get("cloud_instance_type"));
    item.put("cpuCores", number(row.get("cpu_cores")));
    item.put("memoryGiB", number(row.get("memory_gib")));
    item.put("storageGiB", number(row.get("storage_total_gib")));
    item.put("storageUsedGiB", number(row.get("storage_used_gib")));
    item.put("storageAvailableGiB", number(row.get("storage_available_gib")));
    item.put("cpuUtilizationPercent", number(row.get("cpu_utilization_percent")));
    item.put("memoryUtilizationPercent", number(row.get("memory_utilization_percent")));
    item.put("storageUtilizationPercent", number(row.get("storage_utilization_percent")));
    item.put("masterPort", number(row.get("yb_master_port")));
    item.put("tserverPort", number(row.get("yb_tserver_port")));
    item.put("osVersion", row.get("os_version"));
    item.put("configuration", row.get("configuration"));
    item.put("region", row.get("region_code"));
    item.put("regionName", row.get("region_name"));
    item.put("zone", row.get("zone_name"));
    item.put("locationType", row.get("location_type"));
    item.put("leaderPreference", number(row.get("leader_preference")));
    item.put("isPrimaryRegion", row.get("is_primary_region"));
    item.put("universeId", text(row.get("universe_id")));
    item.put("universeCode", row.get("universe_code"));
    item.put("universeName", row.get("universe_name"));
    item.put("universeStatus", row.get("universe_status"));
    item.put("topology", row.get("topology"));
    item.put("replicationFactor", number(row.get("replication_factor")));
    item.put("yugabyteVersion", row.get("yugabyte_version"));
    item.put("environment", row.get("environment_code"));
    item.put("cloud", row.get("cloud_code"));
    item.put("updatedAt", row.get("updated_at"));
    return item;
  }

  private Map<String, Object> tableSummary(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("tableId", text(row.get("table_id")));
    item.put("qualifiedName", qualifiedName(row));
    item.put("tableName", row.get("table_name"));
    item.put("tableType", row.get("table_type"));
    item.put("status", row.get("status"));
    item.put("dataOwnerId", row.get("data_owner_id"));
    item.put("ownerName", row.get("owner_name"));
    item.put("schemaId", text(row.get("schema_id")));
    item.put("schemaName", row.get("schema_name"));
    item.put("databaseId", text(row.get("database_id")));
    item.put("databaseName", row.get("database_name"));
    item.put("universeId", text(row.get("universe_id")));
    item.put("universeCode", row.get("universe_code"));
    item.put("universeName", row.get("universe_name"));
    item.put("environment", row.get("environment_code"));
    item.put("cloud", row.get("cloud_code"));
    item.put("updatedAt", row.get("updated_at"));
    return item;
  }

  private static String qualifiedName(Map<String, Object> row) {
    return String.join(".",
        String.valueOf(row.get("cloud_code")),
        String.valueOf(row.get("environment_code")),
        String.valueOf(row.get("universe_code")),
        String.valueOf(row.get("database_name")),
        String.valueOf(row.get("schema_name")),
        String.valueOf(row.get("table_name")));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> ysql(Object configuration) {
    Object raw = jsonObject(configuration).get("ysql");
    Map<String, Object> ysql = new LinkedHashMap<>();
    if (raw instanceof Map<?, ?> map) {
      ysql.put("enabled", map.get("enabled"));
      ysql.put("port", map.get("port"));
    }
    return ysql;
  }

  private Map<String, Object> capacity(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("totalNodes", number(row.get("total_nodes")));
    item.put("totalCpuCores", number(row.get("total_cpu")));
    item.put("totalMemoryGiB", number(row.get("total_memory")));
    item.put("totalStorageGiB", number(row.get("total_storage")));
    item.put("usedStorageGiB", number(row.get("used_storage")));
    return item;
  }

  private Map<String, Object> contact(Object name, Object email) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("name", name);
    item.put("email", email);
    return item;
  }

  private String formatContact(Object name, Object email) {
    if (name == null && email == null) {
      return null;
    }
    if (name != null && email != null) {
      return name + " <" + email + ">";
    }
    return name != null ? name.toString() : email.toString();
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(sql, (rs, rowNum) -> read(rs), args);
  }

  private Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> found = rows(sql, args);
    return found.isEmpty() ? null : found.get(0);
  }

  private int count(String sql, Object... args) {
    Integer total = jdbc.queryForObject(sql, Integer.class, args);
    return total == null ? 0 : total;
  }

  private Map<String, Object> read(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      row.put(meta.getColumnLabel(i), convert(rs.getObject(i)));
    }
    return row;
  }

  private String timestamp(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MILLIS));
  }

  private Object convert(Object value) {
    if (value instanceof PGobject pg) {
      if (pg.getValue() == null) {
        return null;
      }
      try {
        return mapper.readValue(pg.getValue(), Object.class);
      } catch (Exception exception) {
        return pg.getValue();
      }
    }
    if (value instanceof UUID || value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof OffsetDateTime time) {
      return timestamp(time.toInstant());
    }
    if (value instanceof Timestamp time) {
      return timestamp(time.toInstant());
    }
    if (value instanceof BigDecimal decimal) {
      BigDecimal plain = decimal.stripTrailingZeros();
      if (plain.scale() <= 0) {
        return plain.longValue();
      }
      return plain.doubleValue();
    }
    if (value instanceof Number number) {
      return number;
    }
    return value;
  }

  private Object raw(Object value) {
    return value;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> jsonObject(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    return Map.of();
  }

  private static boolean sameId(Object left, Object right) {
    return left != null && left.equals(right);
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static Number number(Object value) {
    return value instanceof Number number ? number : null;
  }

  private static boolean isUuid(String value) {
    return value != null && UUID_RE.matcher(value).matches();
  }

  private static UUID uuidOrNotFound(String value, String label) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new NotFoundException(label + " " + value + " was not found");
    }
  }

  private static String upper(String value) {
    String trimmed = blankToNull(value);
    return trimmed == null ? null : trimmed.toUpperCase();
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value;
  }

  private record TableBundle(
      Map<String, Object> table,
      List<Map<String, Object>> columns,
      List<Map<String, Object>> indexes,
      List<Map<String, Object>> indexColumns) {}

  private static final class SqlFilter {
    private final StringBuilder clause = new StringBuilder();
    private final List<Object> params = new ArrayList<>();

    void eq(String column, String value) {
      if (value == null) {
        return;
      }
      append(column + "::text = ?");
      params.add(value);
    }

    void eqLower(String column, String value) {
      if (value == null) {
        return;
      }
      append("lower(" + column + ") = ?");
      params.add(value.toLowerCase(java.util.Locale.ROOT));
    }

    void ilike(String column, String value) {
      if (value == null) {
        return;
      }
      append(column + " ILIKE ?");
      params.add("%" + value + "%");
    }

    void append(String expression) {
      clause.append(clause.isEmpty() ? " WHERE " : " AND ").append(expression);
    }

    String where() {
      return clause.toString();
    }

    Object[] args() {
      return params.toArray();
    }
  }
}
