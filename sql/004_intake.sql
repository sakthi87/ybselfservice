-- Yugabyte intake workflow. Objects are created in DEV, then deployed onward.
-- Apply on database yugabyte_metadata.

SET search_path TO catalog, public;

CREATE TABLE IF NOT EXISTS catalog.intake_user (
    user_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name     VARCHAR(255) NOT NULL,
    email         VARCHAR(320) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_intake_user_email UNIQUE (email),
    CONSTRAINT ck_intake_user_name CHECK (length(trim(full_name)) > 0),
    CONSTRAINT ck_intake_user_email CHECK (position('@' IN email) > 1)
);

CREATE TABLE IF NOT EXISTS catalog.intake_project (
    project_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL,
    project_name      VARCHAR(255) NOT NULL,
    description       TEXT,
    business_line     VARCHAR(100),
    tech_owner_email  VARCHAR(320),
    edai_req_id       VARCHAR(255),
    fund_type         VARCHAR(50),
    fund_value        NUMERIC(18, 2),
    sub_domain        VARCHAR(100),
    domain            VARCHAR(100),
    developer_email   VARCHAR(320),
    exp_dev_date      DATE,
    exp_it_date       DATE,
    uat_date          DATE,
    prod_date         DATE,
    request_type      VARCHAR(20) NOT NULL DEFAULT 'NEW',
    status            VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intake_project_user
        FOREIGN KEY (user_id) REFERENCES catalog.intake_user(user_id) ON DELETE CASCADE,
    CONSTRAINT uq_intake_project_name UNIQUE (user_id, project_name),
    CONSTRAINT ck_intake_project_name CHECK (length(trim(project_name)) > 0)
);

CREATE TABLE IF NOT EXISTS catalog.intake_database (
    database_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL,
    database_name   VARCHAR(63) NOT NULL,
    environment     VARCHAR(10) NOT NULL DEFAULT 'DEV',
    status          VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intake_database_project
        FOREIGN KEY (project_id) REFERENCES catalog.intake_project(project_id) ON DELETE CASCADE,
    CONSTRAINT uq_intake_database UNIQUE (project_id, database_name),
    CONSTRAINT ck_intake_database_env CHECK (environment = 'DEV'),
    CONSTRAINT ck_intake_database_name CHECK (database_name ~ '^[A-Za-z_][A-Za-z0-9_]*$')
);

CREATE TABLE IF NOT EXISTS catalog.intake_schema (
    schema_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    database_id    UUID NOT NULL,
    schema_name    VARCHAR(63) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intake_schema_database
        FOREIGN KEY (database_id) REFERENCES catalog.intake_database(database_id) ON DELETE CASCADE,
    CONSTRAINT uq_intake_schema UNIQUE (database_id, schema_name),
    CONSTRAINT ck_intake_schema_public CHECK (lower(schema_name) <> 'public'),
    CONSTRAINT ck_intake_schema_name CHECK (schema_name ~ '^[A-Za-z_][A-Za-z0-9_]*$')
);

CREATE TABLE IF NOT EXISTS catalog.intake_table (
    table_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_id     UUID NOT NULL,
    table_name    VARCHAR(63) NOT NULL,
    description   TEXT,
    ddl           TEXT NOT NULL,
    source        VARCHAR(20) NOT NULL DEFAULT 'DESIGN',
    data_owner_id VARCHAR(100),
    table_context_id UUID,
    status        VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intake_table_schema
        FOREIGN KEY (schema_id) REFERENCES catalog.intake_schema(schema_id) ON DELETE CASCADE,
    CONSTRAINT uq_intake_table UNIQUE (schema_id, table_name),
    CONSTRAINT ck_intake_table_source CHECK (source IN ('DESIGN', 'UPLOAD')),
    CONSTRAINT ck_intake_table_name CHECK (table_name ~ '^[A-Za-z_][A-Za-z0-9_]*$')
);

CREATE TABLE IF NOT EXISTS catalog.intake_column (
    column_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    table_id             UUID NOT NULL,
    column_name          VARCHAR(63) NOT NULL,
    data_type            VARCHAR(100) NOT NULL,
    is_nullable          BOOLEAN NOT NULL DEFAULT TRUE,
    is_primary_key       BOOLEAN NOT NULL DEFAULT FALSE,
    ordinal_position     INTEGER NOT NULL,
    default_expression   TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intake_column_table
        FOREIGN KEY (table_id) REFERENCES catalog.intake_table(table_id) ON DELETE CASCADE,
    CONSTRAINT uq_intake_column UNIQUE (table_id, column_name),
    CONSTRAINT ck_intake_column_pos CHECK (ordinal_position > 0)
);

CREATE TABLE IF NOT EXISTS catalog.intake_index (
    index_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    table_id      UUID NOT NULL,
    index_name    VARCHAR(63) NOT NULL,
    column_names  VARCHAR(500) NOT NULL,
    is_unique     BOOLEAN NOT NULL DEFAULT FALSE,
    ddl           TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intake_index_table
        FOREIGN KEY (table_id) REFERENCES catalog.intake_table(table_id) ON DELETE CASCADE,
    CONSTRAINT uq_intake_index UNIQUE (table_id, index_name)
);

CREATE TABLE IF NOT EXISTS catalog.intake_deployment (
    deployment_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    database_id          UUID NOT NULL,
    target_environment   VARCHAR(10) NOT NULL,
    target_database      VARCHAR(63) NOT NULL,
    status               VARCHAR(30) NOT NULL,
    message              TEXT,
    deployed_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_intake_deployment_database
        FOREIGN KEY (database_id) REFERENCES catalog.intake_database(database_id) ON DELETE CASCADE,
    CONSTRAINT ck_intake_deployment_env CHECK (target_environment IN ('IT', 'UAT', 'PROD'))
);

GRANT SELECT ON catalog.intake_user, catalog.intake_project, catalog.intake_database,
    catalog.intake_schema, catalog.intake_table, catalog.intake_column,
    catalog.intake_index, catalog.intake_deployment TO metadata_read;
