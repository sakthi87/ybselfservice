-- Each visit that changes a project shares one request id on the audit log.
-- Apply on database yugabyte_metadata.

SET search_path TO catalog, public;

ALTER TABLE catalog.platform_activity
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(40),
    ADD COLUMN IF NOT EXISTS description TEXT;

CREATE INDEX IF NOT EXISTS ix_platform_activity_request
    ON catalog.platform_activity (request_id);
