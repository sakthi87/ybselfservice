-- Each project request keeps the intake id assigned when it was submitted.
-- Apply on database yugabyte_metadata.

SET search_path TO catalog, public;

ALTER TABLE catalog.intake_project
    ADD COLUMN IF NOT EXISTS intake_id VARCHAR(40);

UPDATE catalog.intake_project project
   SET intake_id = (
       SELECT activity.request_id
         FROM catalog.platform_activity activity
         JOIN catalog.intake_user owner ON owner.user_id = project.user_id
        WHERE activity.action = 'INTAKE_SUBMITTED'
          AND activity.request_id IS NOT NULL
          AND activity.object_name = project.project_name
          AND lower(owner.email) = lower(activity.user_email)
        ORDER BY activity.created_at
        LIMIT 1
   )
 WHERE project.intake_id IS NULL
   AND EXISTS (
       SELECT 1
         FROM catalog.platform_activity activity
         JOIN catalog.intake_user owner ON owner.user_id = project.user_id
        WHERE activity.action = 'INTAKE_SUBMITTED'
          AND activity.request_id IS NOT NULL
          AND activity.object_name = project.project_name
          AND lower(owner.email) = lower(activity.user_email)
   );

UPDATE catalog.intake_project
   SET intake_id = 'REQ-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 8))
 WHERE intake_id IS NULL;

ALTER TABLE catalog.intake_project
    ALTER COLUMN intake_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_intake_project_intake_id
    ON catalog.intake_project (intake_id);
