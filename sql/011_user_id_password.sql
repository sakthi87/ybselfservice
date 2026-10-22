-- Sign-in identity is a user id plus a password hash.
-- Apply on database yugabyte_metadata. Does not reload sample data.

SET search_path TO catalog, public;

ALTER TABLE catalog.intake_user ADD COLUMN IF NOT EXISTS login_id VARCHAR(64);
ALTER TABLE catalog.intake_user ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

UPDATE catalog.intake_user
   SET login_id = lower(split_part(email, '@', 1))
 WHERE login_id IS NULL
   AND email IS NOT NULL
   AND position('@' IN email) > 1;

ALTER TABLE catalog.intake_user ALTER COLUMN full_name DROP NOT NULL;
ALTER TABLE catalog.intake_user ALTER COLUMN email DROP NOT NULL;
ALTER TABLE catalog.intake_user DROP CONSTRAINT IF EXISTS ck_intake_user_name;
ALTER TABLE catalog.intake_user DROP CONSTRAINT IF EXISTS ck_intake_user_email;
ALTER TABLE catalog.intake_user DROP CONSTRAINT IF EXISTS uq_intake_user_email;

CREATE UNIQUE INDEX IF NOT EXISTS ux_intake_user_login
    ON catalog.intake_user (lower(login_id));
CREATE UNIQUE INDEX IF NOT EXISTS ux_intake_user_email
    ON catalog.intake_user (lower(email))
    WHERE email IS NOT NULL;

ALTER TABLE catalog.data_owner ADD COLUMN IF NOT EXISTS platform_user_id VARCHAR(64);

UPDATE catalog.data_owner
   SET platform_user_id = lower(split_part(primary_contact_email, '@', 1))
 WHERE platform_user_id IS NULL
   AND primary_contact_email IS NOT NULL
   AND position('@' IN primary_contact_email) > 1;

ALTER TABLE catalog.data_owner DROP CONSTRAINT IF EXISTS ck_primary_contact;
ALTER TABLE catalog.data_owner ADD CONSTRAINT ck_primary_contact CHECK (
    length(trim(primary_contact_name)) > 0
    AND (primary_contact_email IS NULL OR length(trim(primary_contact_email)) > 3)
);

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
    ctx.actual_tps,
    o.platform_user_id
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
