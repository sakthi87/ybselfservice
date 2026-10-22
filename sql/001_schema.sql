-- Dedicated YugabyteDB metadata catalog.
-- Apply with: \c yugabyte_metadata

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS catalog;

SET search_path TO catalog, public;

-- Status / classification enums

CREATE TYPE cloud_provider AS ENUM (
    'AWS',
    'AZURE'
);

CREATE TYPE environment_type AS ENUM (
    'DEV',
    'IT',
    'UAT',
    'PROD'
);

CREATE TYPE universe_status AS ENUM (
    'PROVISIONING',
    'ACTIVE',
    'DEGRADED',
    'MAINTENANCE',
    'DECOMMISSIONING',
    'DECOMMISSIONED'
);

CREATE TYPE topology_type AS ENUM (
    'SINGLE_REGION',
    'MULTI_REGION',
    'STRETCH_CLUSTER'
);

CREATE TYPE node_status AS ENUM (
    'PROVISIONING',
    'ACTIVE',
    'DEGRADED',
    'MAINTENANCE',
    'DECOMMISSIONED'
);

CREATE TYPE node_role AS ENUM (
    'MASTER',
    'TSERVER',
    'MASTER_TSERVER'
);

CREATE TYPE location_type AS ENUM (
    'CLOUD',
    'ONPREM'
);

CREATE TYPE metadata_status AS ENUM (
    'ACTIVE',
    'DEPRECATED',
    'RETIRED'
);

CREATE TYPE index_status AS ENUM (
    'BUILDING',
    'VALID',
    'INVALID',
    'DROPPED'
);

CREATE TYPE index_kind AS ENUM (
    'PRIMARY_KEY',
    'UNIQUE',
    'NON_UNIQUE',
    'PARTIAL',
    'COVERING'
);

-- Cloud

CREATE TABLE catalog.cloud (
    cloud_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cloud_code      cloud_provider NOT NULL,
    cloud_name      VARCHAR(100) NOT NULL,
    status          metadata_status NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(100) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(100) NOT NULL,
    CONSTRAINT uq_cloud_code UNIQUE (cloud_code),
    CONSTRAINT ck_cloud_name CHECK (length(trim(cloud_name)) > 0)
);

-- Environment

CREATE TABLE catalog.environment (
    environment_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cloud_id            UUID NOT NULL,
    environment_code    environment_type NOT NULL,
    environment_name    VARCHAR(100) NOT NULL,
    status              metadata_status NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(100) NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          VARCHAR(100) NOT NULL,
    CONSTRAINT fk_environment_cloud
        FOREIGN KEY (cloud_id)
        REFERENCES catalog.cloud(cloud_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_cloud_environment UNIQUE (cloud_id, environment_code),
    CONSTRAINT ck_environment_name CHECK (length(trim(environment_name)) > 0)
);

-- Universe

CREATE TABLE catalog.universe (
    universe_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    environment_id          UUID NOT NULL,
    universe_code           VARCHAR(150) NOT NULL,
    universe_name           VARCHAR(255) NOT NULL,
    status                  universe_status NOT NULL DEFAULT 'PROVISIONING',
    topology                topology_type NOT NULL,
    yugabyte_version        VARCHAR(100),
    replication_factor      SMALLINT NOT NULL,
    expected_node_count     SMALLINT NOT NULL,
    region_count            SMALLINT NOT NULL,
    master_count            SMALLINT,
    tserver_count           SMALLINT,
    fault_tolerance_level   VARCHAR(50),
    api_endpoint            TEXT,
    configuration           JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata_version        BIGINT NOT NULL DEFAULT 1,
    source_system           VARCHAR(100) NOT NULL DEFAULT 'metadata-pipeline',
    source_event_id         VARCHAR(255),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(100) NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(100) NOT NULL,
    CONSTRAINT fk_universe_environment
        FOREIGN KEY (environment_id)
        REFERENCES catalog.environment(environment_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_universe_code UNIQUE (environment_id, universe_code),
    CONSTRAINT ck_universe_rf CHECK (replication_factor >= 1),
    CONSTRAINT ck_universe_nodes CHECK (expected_node_count >= 1),
    CONSTRAINT ck_universe_regions CHECK (region_count >= 1),
    CONSTRAINT ck_universe_configuration CHECK (jsonb_typeof(configuration) = 'object')
);

-- Universe region

CREATE TABLE catalog.universe_region (
    universe_region_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    universe_id             UUID NOT NULL,
    location_type           location_type NOT NULL,
    cloud_provider          cloud_provider,
    region_code             VARCHAR(150) NOT NULL,
    region_name             VARCHAR(255) NOT NULL,
    zone_name               VARCHAR(255),
    node_count              SMALLINT NOT NULL DEFAULT 0,
    leader_preference       SMALLINT,
    is_primary_region       BOOLEAN NOT NULL DEFAULT FALSE,
    placement_configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                  metadata_status NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(100) NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(100) NOT NULL,
    CONSTRAINT fk_region_universe
        FOREIGN KEY (universe_id)
        REFERENCES catalog.universe(universe_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_region_cloud CHECK (
        (location_type = 'CLOUD' AND cloud_provider IS NOT NULL)
        OR
        (location_type = 'ONPREM' AND cloud_provider IS NULL)
    ),
    CONSTRAINT ck_region_node_count CHECK (node_count >= 0),
    CONSTRAINT ck_region_leader_preference CHECK (
        leader_preference IS NULL OR leader_preference >= 1
    ),
    CONSTRAINT ck_region_configuration CHECK (jsonb_typeof(placement_configuration) = 'object'),
    CONSTRAINT uq_universe_region UNIQUE (universe_id, region_code, zone_name)
);

-- Universe nodes

CREATE TABLE catalog.universe_node (
    node_id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    universe_id                 UUID NOT NULL,
    universe_region_id          UUID NOT NULL,
    node_name                   VARCHAR(255) NOT NULL,
    host_name                   VARCHAR(500),
    private_ip                  INET,
    public_ip                   INET,
    node_role                   node_role NOT NULL,
    status                      node_status NOT NULL DEFAULT 'PROVISIONING',
    cloud_instance_type         VARCHAR(150),
    cpu_cores                   NUMERIC(8,2),
    memory_gib                  NUMERIC(12,2),
    storage_total_gib           NUMERIC(14,2),
    storage_used_gib            NUMERIC(14,2),
    storage_available_gib       NUMERIC(14,2),
    cpu_utilization_percent     NUMERIC(5,2),
    memory_utilization_percent  NUMERIC(5,2),
    storage_utilization_percent NUMERIC(5,2),
    yb_master_port              INTEGER DEFAULT 7100,
    yb_tserver_port             INTEGER DEFAULT 9000,
    os_version                  VARCHAR(255),
    configuration               JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                  VARCHAR(100) NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                  VARCHAR(100) NOT NULL,
    CONSTRAINT fk_node_universe
        FOREIGN KEY (universe_id)
        REFERENCES catalog.universe(universe_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_node_region
        FOREIGN KEY (universe_region_id)
        REFERENCES catalog.universe_region(universe_region_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_universe_node_name UNIQUE (universe_id, node_name),
    CONSTRAINT ck_cpu CHECK (cpu_cores IS NULL OR cpu_cores > 0),
    CONSTRAINT ck_memory CHECK (memory_gib IS NULL OR memory_gib > 0),
    CONSTRAINT ck_storage CHECK (storage_total_gib IS NULL OR storage_total_gib > 0),
    CONSTRAINT ck_storage_used CHECK (storage_used_gib IS NULL OR storage_used_gib >= 0),
    CONSTRAINT ck_storage_capacity CHECK (
        storage_total_gib IS NULL
        OR storage_used_gib IS NULL
        OR storage_used_gib <= storage_total_gib
    ),
    CONSTRAINT ck_storage_available CHECK (
        storage_available_gib IS NULL OR storage_available_gib >= 0
    ),
    CONSTRAINT ck_cpu_utilization CHECK (
        cpu_utilization_percent IS NULL
        OR (cpu_utilization_percent >= 0 AND cpu_utilization_percent <= 100)
    ),
    CONSTRAINT ck_memory_utilization CHECK (
        memory_utilization_percent IS NULL
        OR (memory_utilization_percent >= 0 AND memory_utilization_percent <= 100)
    ),
    CONSTRAINT ck_storage_utilization CHECK (
        storage_utilization_percent IS NULL
        OR (storage_utilization_percent >= 0 AND storage_utilization_percent <= 100)
    ),
    CONSTRAINT ck_configuration CHECK (jsonb_typeof(configuration) = 'object')
);

-- Data owner

CREATE TABLE catalog.data_owner (
    data_owner_id             VARCHAR(100) PRIMARY KEY,
    owner_name                VARCHAR(255) NOT NULL,
    primary_contact_name      VARCHAR(255) NOT NULL,
    primary_contact_email     VARCHAR(320) NOT NULL,
    secondary_contact_name    VARCHAR(255),
    secondary_contact_email   VARCHAR(320),
    status                    metadata_status NOT NULL DEFAULT 'ACTIVE',
    created_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                VARCHAR(100) NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                VARCHAR(100) NOT NULL,
    CONSTRAINT ck_owner_name CHECK (length(trim(owner_name)) > 0),
    CONSTRAINT ck_primary_contact CHECK (
        length(trim(primary_contact_name)) > 0
        AND length(trim(primary_contact_email)) > 3
    ),
    CONSTRAINT ck_secondary_contact CHECK (
        secondary_contact_name IS NULL
        OR length(trim(secondary_contact_name)) > 0
    ),
    CONSTRAINT ck_secondary_email CHECK (
        secondary_contact_email IS NULL
        OR length(trim(secondary_contact_email)) > 3
    ),
    CONSTRAINT ck_contacts_not_same CHECK (
        lower(primary_contact_email) <> lower(coalesce(secondary_contact_email, ''))
    )
);

-- Database metadata

CREATE TABLE catalog.database_metadata (
    database_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    universe_id            UUID NOT NULL,
    database_name          VARCHAR(255) NOT NULL,
    status                 metadata_status NOT NULL DEFAULT 'ACTIVE',
    database_owner         VARCHAR(255),
    definition_sql         TEXT,
    properties             JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata_version       BIGINT NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by             VARCHAR(100) NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by             VARCHAR(100) NOT NULL,
    CONSTRAINT fk_database_universe
        FOREIGN KEY (universe_id)
        REFERENCES catalog.universe(universe_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_database_universe UNIQUE (universe_id, database_name),
    CONSTRAINT ck_database_name CHECK (length(trim(database_name)) > 0),
    CONSTRAINT ck_database_properties CHECK (jsonb_typeof(properties) = 'object')
);

-- Schema metadata

CREATE TABLE catalog.schema_metadata (
    schema_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    database_id            UUID NOT NULL,
    schema_name            VARCHAR(255) NOT NULL,
    status                 metadata_status NOT NULL DEFAULT 'ACTIVE',
    schema_owner           VARCHAR(255),
    definition_sql         TEXT,
    properties             JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata_version       BIGINT NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by             VARCHAR(100) NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by             VARCHAR(100) NOT NULL,
    CONSTRAINT fk_schema_database
        FOREIGN KEY (database_id)
        REFERENCES catalog.database_metadata(database_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_schema_database UNIQUE (database_id, schema_name),
    CONSTRAINT ck_schema_name CHECK (length(trim(schema_name)) > 0),
    CONSTRAINT ck_schema_properties CHECK (jsonb_typeof(properties) = 'object')
);

-- Shared business and capacity context for one or more tables.

CREATE TABLE catalog.table_context (
    table_context_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    car_id                    VARCHAR(100),
    business_line             VARCHAR(100),
    product_line              VARCHAR(100),
    system_of_origin_code     VARCHAR(100),
    estimated_storage_volume  BIGINT,
    actual_storage_volume     BIGINT,
    estimated_tps             INTEGER,
    actual_tps                INTEGER,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                VARCHAR(100) NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                VARCHAR(100) NOT NULL,
    CONSTRAINT ck_context_estimated_storage CHECK (
        estimated_storage_volume IS NULL OR estimated_storage_volume >= 0
    ),
    CONSTRAINT ck_context_actual_storage CHECK (
        actual_storage_volume IS NULL OR actual_storage_volume >= 0
    ),
    CONSTRAINT ck_context_estimated_tps CHECK (
        estimated_tps IS NULL OR estimated_tps >= 0
    ),
    CONSTRAINT ck_context_actual_tps CHECK (
        actual_tps IS NULL OR actual_tps >= 0
    )
);

-- Table metadata

CREATE TABLE catalog.table_metadata (
    table_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_id                UUID NOT NULL,
    data_owner_id            VARCHAR(100) NOT NULL,
    table_context_id         UUID,
    table_name               VARCHAR(255) NOT NULL,
    table_type               VARCHAR(50) NOT NULL DEFAULT 'BASE_TABLE',
    status                   metadata_status NOT NULL DEFAULT 'ACTIVE',
    is_partitioned           BOOLEAN NOT NULL DEFAULT FALSE,
    partition_strategy       VARCHAR(50),
    partition_definition     TEXT,
    tablespace_name          VARCHAR(255),
    row_count_estimate       BIGINT,
    size_bytes               BIGINT,
    definition_sql           TEXT NOT NULL,
    properties               JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata_version         BIGINT NOT NULL DEFAULT 1,
    source_system            VARCHAR(100) NOT NULL DEFAULT 'metadata-pipeline',
    source_event_id          VARCHAR(255),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               VARCHAR(100) NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by               VARCHAR(100) NOT NULL,
    CONSTRAINT fk_table_schema
        FOREIGN KEY (schema_id)
        REFERENCES catalog.schema_metadata(schema_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_table_owner
        FOREIGN KEY (data_owner_id)
        REFERENCES catalog.data_owner(data_owner_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_table_context
        FOREIGN KEY (table_context_id)
        REFERENCES catalog.table_context(table_context_id)
        ON DELETE SET NULL,
    CONSTRAINT uq_table_schema UNIQUE (schema_id, table_name),
    CONSTRAINT ck_table_name CHECK (length(trim(table_name)) > 0),
    CONSTRAINT ck_table_type CHECK (
        table_type IN (
            'BASE_TABLE',
            'PARTITION',
            'VIEW',
            'MATERIALIZED_VIEW',
            'FOREIGN_TABLE'
        )
    ),
    CONSTRAINT ck_partition_definition CHECK (
        is_partitioned = FALSE OR partition_definition IS NOT NULL
    ),
    CONSTRAINT ck_row_count CHECK (
        row_count_estimate IS NULL OR row_count_estimate >= 0
    ),
    CONSTRAINT ck_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_table_properties CHECK (jsonb_typeof(properties) = 'object')
);

-- Column metadata

CREATE TABLE catalog.column_metadata (
    column_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    table_id                UUID NOT NULL,
    column_name             VARCHAR(255) NOT NULL,
    ordinal_position        INTEGER NOT NULL,
    data_type               VARCHAR(255) NOT NULL,
    udt_name                VARCHAR(255),
    character_max_length    INTEGER,
    numeric_precision       INTEGER,
    numeric_scale           INTEGER,
    is_nullable             BOOLEAN NOT NULL DEFAULT TRUE,
    is_identity             BOOLEAN NOT NULL DEFAULT FALSE,
    identity_generation     VARCHAR(30),
    is_generated            BOOLEAN NOT NULL DEFAULT FALSE,
    default_expression      TEXT,
    generated_expression    TEXT,
    collation_name          VARCHAR(255),
    comment                 TEXT,
    status                  metadata_status NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(100) NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(100) NOT NULL,
    CONSTRAINT fk_column_table
        FOREIGN KEY (table_id)
        REFERENCES catalog.table_metadata(table_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_column_name UNIQUE (table_id, column_name),
    CONSTRAINT uq_column_position UNIQUE (table_id, ordinal_position),
    CONSTRAINT ck_ordinal CHECK (ordinal_position > 0),
    CONSTRAINT ck_character_length CHECK (
        character_max_length IS NULL OR character_max_length >= 0
    ),
    CONSTRAINT ck_numeric_precision CHECK (
        numeric_precision IS NULL OR numeric_precision >= 0
    ),
    CONSTRAINT ck_identity_generation CHECK (
        is_identity = FALSE
        OR identity_generation IN ('ALWAYS', 'BY DEFAULT')
    )
);

-- Index metadata

CREATE TABLE catalog.index_metadata (
    index_id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    table_id                 UUID NOT NULL,
    index_name               VARCHAR(255) NOT NULL,
    index_kind               index_kind NOT NULL,
    index_method             VARCHAR(50) NOT NULL DEFAULT 'LSM',
    status                   index_status NOT NULL DEFAULT 'VALID',
    is_unique                BOOLEAN NOT NULL DEFAULT FALSE,
    is_primary               BOOLEAN NOT NULL DEFAULT FALSE,
    is_partial               BOOLEAN NOT NULL DEFAULT FALSE,
    predicate                TEXT,
    index_definition         TEXT NOT NULL,
    tablespace_name          VARCHAR(255),
    properties               JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               VARCHAR(100) NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by               VARCHAR(100) NOT NULL,
    CONSTRAINT fk_index_table
        FOREIGN KEY (table_id)
        REFERENCES catalog.table_metadata(table_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_index_name UNIQUE (table_id, index_name),
    CONSTRAINT ck_primary_unique CHECK (is_primary = FALSE OR is_unique = TRUE),
    CONSTRAINT ck_partial_predicate CHECK (is_partial = FALSE OR predicate IS NOT NULL),
    CONSTRAINT ck_index_properties CHECK (jsonb_typeof(properties) = 'object')
);

-- Index columns

CREATE TABLE catalog.index_column_metadata (
    index_column_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    index_id                UUID NOT NULL,
    ordinal_position        INTEGER NOT NULL,
    column_name             VARCHAR(255),
    expression              TEXT,
    sort_direction          VARCHAR(10) NOT NULL DEFAULT 'ASC',
    nulls_position          VARCHAR(10) NOT NULL DEFAULT 'LAST',
    is_included_column      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(100) NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(100) NOT NULL,
    CONSTRAINT fk_index_column_index
        FOREIGN KEY (index_id)
        REFERENCES catalog.index_metadata(index_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_index_column_position UNIQUE (index_id, ordinal_position),
    CONSTRAINT ck_index_column_reference CHECK (
        column_name IS NOT NULL OR expression IS NOT NULL
    ),
    CONSTRAINT ck_sort_direction CHECK (sort_direction IN ('ASC', 'DESC')),
    CONSTRAINT ck_nulls_position CHECK (nulls_position IN ('FIRST', 'LAST'))
);

-- API access paths

CREATE INDEX ix_environment_cloud
    ON catalog.environment (cloud_id, environment_code);

CREATE INDEX ix_universe_environment
    ON catalog.universe (environment_id, status);

CREATE INDEX ix_universe_region_universe
    ON catalog.universe_region (universe_id, status);

CREATE INDEX ix_node_universe
    ON catalog.universe_node (universe_id, status);

CREATE INDEX ix_node_region
    ON catalog.universe_node (universe_region_id, status);

CREATE INDEX ix_database_universe
    ON catalog.database_metadata (universe_id, status);

CREATE INDEX ix_schema_database
    ON catalog.schema_metadata (database_id, status);

CREATE INDEX ix_table_schema
    ON catalog.table_metadata (schema_id, status);

CREATE INDEX ix_table_owner
    ON catalog.table_metadata (data_owner_id, status);

CREATE INDEX ix_table_context
    ON catalog.table_metadata (table_context_id);

CREATE INDEX ix_table_status
    ON catalog.table_metadata (status);

CREATE INDEX ix_index_table
    ON catalog.index_metadata (table_id, status);

CREATE INDEX ix_index_column_index
    ON catalog.index_column_metadata (index_id);

-- Audit trigger

CREATE OR REPLACE FUNCTION catalog.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_cloud_updated_at
BEFORE UPDATE ON catalog.cloud
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_environment_updated_at
BEFORE UPDATE ON catalog.environment
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_universe_updated_at
BEFORE UPDATE ON catalog.universe
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_universe_region_updated_at
BEFORE UPDATE ON catalog.universe_region
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_universe_node_updated_at
BEFORE UPDATE ON catalog.universe_node
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_owner_updated_at
BEFORE UPDATE ON catalog.data_owner
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_table_context_updated_at
BEFORE UPDATE ON catalog.table_context
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_database_updated_at
BEFORE UPDATE ON catalog.database_metadata
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_schema_updated_at
BEFORE UPDATE ON catalog.schema_metadata
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_table_updated_at
BEFORE UPDATE ON catalog.table_metadata
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_column_updated_at
BEFORE UPDATE ON catalog.column_metadata
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_index_updated_at
BEFORE UPDATE ON catalog.index_metadata
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

CREATE TRIGGER tr_index_column_updated_at
BEFORE UPDATE ON catalog.index_column_metadata
FOR EACH ROW EXECUTE FUNCTION catalog.set_updated_at();

-- Ownership validation trigger

CREATE OR REPLACE FUNCTION catalog.validate_table_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    owner_status catalog.metadata_status;
BEGIN
    SELECT status
      INTO owner_status
      FROM catalog.data_owner
     WHERE data_owner_id = NEW.data_owner_id;

    IF owner_status IS NULL THEN
        RAISE EXCEPTION 'Data owner % does not exist', NEW.data_owner_id;
    END IF;

    IF owner_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'Data owner % is not ACTIVE', NEW.data_owner_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_validate_table_owner
BEFORE INSERT OR UPDATE OF data_owner_id
ON catalog.table_metadata
FOR EACH ROW
EXECUTE FUNCTION catalog.validate_table_owner();

-- Read models for the API

CREATE OR REPLACE VIEW catalog.v_clouds AS
SELECT
    c.cloud_id,
    c.cloud_code,
    c.cloud_name,
    c.status
FROM catalog.cloud c
WHERE c.status = 'ACTIVE';

CREATE OR REPLACE VIEW catalog.v_universe_summary AS
SELECT
    u.universe_id,
    u.universe_code,
    u.universe_name,
    c.cloud_code,
    c.cloud_name,
    e.environment_code,
    e.environment_name,
    u.status,
    u.topology,
    u.yugabyte_version,
    u.replication_factor,
    u.expected_node_count,
    u.region_count,
    u.master_count,
    u.tserver_count,
    u.fault_tolerance_level,
    u.configuration,
    u.created_at,
    u.updated_at
FROM catalog.universe u
JOIN catalog.environment e
  ON e.environment_id = u.environment_id
JOIN catalog.cloud c
  ON c.cloud_id = e.cloud_id
WHERE u.status <> 'DECOMMISSIONED';

CREATE OR REPLACE VIEW catalog.v_table_catalog AS
SELECT
    t.table_id,
    t.table_name,
    t.table_type,
    t.status,
    t.data_owner_id,
    o.owner_name,
    o.primary_contact_name,
    o.primary_contact_email,
    o.secondary_contact_name,
    o.secondary_contact_email,
    s.schema_id,
    s.schema_name,
    d.database_id,
    d.database_name,
    u.universe_id,
    u.universe_code,
    u.universe_name,
    e.environment_code,
    c.cloud_code,
    t.updated_at,
    ctx.table_context_id,
    ctx.car_id,
    ctx.business_line,
    ctx.product_line,
    ctx.system_of_origin_code,
    ctx.estimated_storage_volume,
    ctx.actual_storage_volume,
    ctx.estimated_tps,
    ctx.actual_tps
FROM catalog.table_metadata t
JOIN catalog.data_owner o
  ON o.data_owner_id = t.data_owner_id
LEFT JOIN catalog.table_context ctx
  ON ctx.table_context_id = t.table_context_id
JOIN catalog.schema_metadata s
  ON s.schema_id = t.schema_id
JOIN catalog.database_metadata d
  ON d.database_id = s.database_id
JOIN catalog.universe u
  ON u.universe_id = d.universe_id
JOIN catalog.environment e
  ON e.environment_id = u.environment_id
JOIN catalog.cloud c
  ON c.cloud_id = e.cloud_id
WHERE t.status <> 'RETIRED';

CREATE OR REPLACE VIEW catalog.v_node_catalog AS
SELECT
    n.node_id,
    n.node_name,
    n.host_name,
    host(n.private_ip) AS private_ip,
    host(n.public_ip) AS public_ip,
    n.node_role,
    n.status,
    n.cloud_instance_type,
    n.cpu_cores,
    n.memory_gib,
    n.storage_total_gib,
    n.storage_used_gib,
    n.storage_available_gib,
    n.cpu_utilization_percent,
    n.memory_utilization_percent,
    n.storage_utilization_percent,
    n.yb_master_port,
    n.yb_tserver_port,
    n.os_version,
    n.configuration,
    n.created_at,
    n.updated_at,
    r.universe_region_id,
    r.region_code,
    r.region_name,
    r.zone_name,
    r.location_type,
    r.cloud_provider,
    r.leader_preference,
    r.is_primary_region,
    r.node_count AS region_node_count,
    u.universe_id,
    u.universe_code,
    u.universe_name,
    u.status AS universe_status,
    u.topology,
    u.replication_factor,
    u.yugabyte_version,
    u.fault_tolerance_level,
    e.environment_code,
    e.environment_name,
    c.cloud_code,
    c.cloud_name
FROM catalog.universe_node n
JOIN catalog.universe_region r
  ON r.universe_region_id = n.universe_region_id
JOIN catalog.universe u
  ON u.universe_id = n.universe_id
JOIN catalog.environment e
  ON e.environment_id = u.environment_id
JOIN catalog.cloud c
  ON c.cloud_id = e.cloud_id;

-- Database roles

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'metadata_read') THEN
        CREATE ROLE metadata_read;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'metadata_write') THEN
        CREATE ROLE metadata_write;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'metadata_admin') THEN
        CREATE ROLE metadata_admin;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA catalog TO metadata_read, metadata_write, metadata_admin;

GRANT SELECT ON ALL TABLES IN SCHEMA catalog TO metadata_read;

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA catalog TO metadata_write;

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA catalog TO metadata_admin;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA catalog TO metadata_admin;

ALTER DEFAULT PRIVILEGES IN SCHEMA catalog
    GRANT SELECT ON TABLES TO metadata_read;

ALTER DEFAULT PRIVILEGES IN SCHEMA catalog
    GRANT SELECT, INSERT, UPDATE ON TABLES TO metadata_write;
