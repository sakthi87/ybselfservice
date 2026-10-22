-- Extra project details collected on the intake form.
-- Apply on database yugabyte_metadata.

SET search_path TO catalog, public;

ALTER TABLE catalog.intake_project
    ADD COLUMN IF NOT EXISTS car_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS car_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS business_line_owner VARCHAR(255),
    ADD COLUMN IF NOT EXISTS support_assignment_group VARCHAR(255);
