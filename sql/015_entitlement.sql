-- One entitlement per catalog database. Tables in that database display it.
-- People are associated here for display. Access requests are made in another portal.
-- Apply on database yugabyte_metadata. Safe to re-run.

SET search_path TO catalog, public;

CREATE TABLE IF NOT EXISTS catalog.entitlement (
    entitlement_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    database_id       UUID NOT NULL,
    entitlement_code  VARCHAR(100) NOT NULL,
    entitlement_name  VARCHAR(255) NOT NULL,
    description       TEXT,
    status            metadata_status NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(100) NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(100) NOT NULL,
    CONSTRAINT fk_entitlement_database
        FOREIGN KEY (database_id)
        REFERENCES catalog.database_metadata(database_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_entitlement_database UNIQUE (database_id),
    CONSTRAINT uq_entitlement_code UNIQUE (entitlement_code),
    CONSTRAINT ck_entitlement_code CHECK (length(trim(entitlement_code)) > 0),
    CONSTRAINT ck_entitlement_name CHECK (length(trim(entitlement_name)) > 0)
);

CREATE TABLE IF NOT EXISTS catalog.entitlement_member (
    member_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entitlement_id   UUID NOT NULL,
    user_id          VARCHAR(64) NOT NULL,
    full_name        VARCHAR(255) NOT NULL,
    email            VARCHAR(320),
    association      VARCHAR(40) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       VARCHAR(100) NOT NULL,
    CONSTRAINT fk_entitlement_member
        FOREIGN KEY (entitlement_id)
        REFERENCES catalog.entitlement(entitlement_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_entitlement_member UNIQUE (entitlement_id, user_id),
    CONSTRAINT ck_entitlement_member_user CHECK (length(trim(user_id)) > 0),
    CONSTRAINT ck_entitlement_member_name CHECK (length(trim(full_name)) > 0),
    CONSTRAINT ck_entitlement_association CHECK (association IN ('OWNER', 'MEMBER'))
);

INSERT INTO catalog.entitlement (
    entitlement_id, database_id, entitlement_code, entitlement_name, description, created_by, updated_by
)
VALUES
    ('80000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
     'ENT-CUSTOMER', 'Customer database',
     'Access to the customer database. Tables in this database carry this entitlement.',
     'bootstrap', 'bootstrap'),
    ('80000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000002',
     'ENT-FINANCE', 'Finance database',
     'Access to the finance database. Tables in this database carry this entitlement.',
     'bootstrap', 'bootstrap')
ON CONFLICT (entitlement_id) DO NOTHING;

INSERT INTO catalog.entitlement_member (
    member_id, entitlement_id, user_id, full_name, email, association, created_by
)
VALUES
    ('81000000-0000-0000-0000-000000000001', '80000000-0000-0000-0000-000000000001',
     'john.smith', 'John Smith', 'john.smith@example.com', 'OWNER', 'bootstrap'),
    ('81000000-0000-0000-0000-000000000002', '80000000-0000-0000-0000-000000000001',
     'jane.doe', 'Jane Doe', 'jane.doe@example.com', 'MEMBER', 'bootstrap'),
    ('81000000-0000-0000-0000-000000000003', '80000000-0000-0000-0000-000000000002',
     'alice.brown', 'Alice Brown', 'alice.brown@example.com', 'OWNER', 'bootstrap'),
    ('81000000-0000-0000-0000-000000000004', '80000000-0000-0000-0000-000000000002',
     'bob.green', 'Bob Green', 'bob.green@example.com', 'MEMBER', 'bootstrap')
ON CONFLICT (member_id) DO NOTHING;

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
    env.environment_code,
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
    ctx.actual_tps,
    o.platform_user_id,
    ent.entitlement_id,
    ent.entitlement_code,
    ent.entitlement_name
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
JOIN catalog.environment env
  ON env.environment_id = u.environment_id
JOIN catalog.cloud c
  ON c.cloud_id = env.cloud_id
LEFT JOIN catalog.entitlement ent
  ON ent.database_id = d.database_id
 AND ent.status = 'ACTIVE'
WHERE t.status <> 'RETIRED';

GRANT SELECT ON catalog.entitlement, catalog.entitlement_member TO metadata_read;
GRANT SELECT, INSERT, UPDATE ON catalog.entitlement, catalog.entitlement_member TO metadata_write;
