-- User identity for intake is catalog.intake_user.
-- This log records what that user did on the platform.
-- Apply on database yugabyte_metadata.

SET search_path TO catalog, public;

ALTER TABLE catalog.intake_project
    ADD COLUMN IF NOT EXISTS request_type VARCHAR(20) NOT NULL DEFAULT 'NEW';

CREATE TABLE IF NOT EXISTS catalog.platform_activity (
    activity_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_email    VARCHAR(320) NOT NULL,
    action        VARCHAR(80) NOT NULL,
    object_type   VARCHAR(40),
    object_name   VARCHAR(255),
    detail        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_platform_activity_user
    ON catalog.platform_activity (user_email, created_at DESC);

GRANT SELECT ON catalog.platform_activity TO metadata_read;
