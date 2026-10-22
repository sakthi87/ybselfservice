-- Identity key supplied by the login service. Existing rows keep a null subject until the next sign-in.

ALTER TABLE catalog.intake_user
    ADD COLUMN IF NOT EXISTS subject_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS ux_intake_user_subject
    ON catalog.intake_user (subject_id)
    WHERE subject_id IS NOT NULL;
