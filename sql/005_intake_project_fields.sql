-- ODP360 Intake project fields, plus links from a designed table to the catalog owner and context.
-- Apply on database yugabyte_metadata after 004_intake.sql.

SET search_path TO catalog, public;

ALTER TABLE catalog.intake_project
    ADD COLUMN IF NOT EXISTS edai_req_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS fund_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS fund_value NUMERIC(18, 2),
    ADD COLUMN IF NOT EXISTS sub_domain VARCHAR(100),
    ADD COLUMN IF NOT EXISTS domain VARCHAR(100),
    ADD COLUMN IF NOT EXISTS developer_email VARCHAR(320),
    ADD COLUMN IF NOT EXISTS exp_dev_date DATE,
    ADD COLUMN IF NOT EXISTS exp_it_date DATE,
    ADD COLUMN IF NOT EXISTS uat_date DATE,
    ADD COLUMN IF NOT EXISTS prod_date DATE;

ALTER TABLE catalog.intake_table
    ADD COLUMN IF NOT EXISTS data_owner_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS table_context_id UUID;
