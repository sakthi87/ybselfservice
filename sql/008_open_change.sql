-- An existing-project visit is one open change until the user finishes or deletes it.
-- Apply on database yugabyte_metadata.

SET search_path TO catalog, public;

CREATE TABLE IF NOT EXISTS catalog.intake_change (
    request_id   VARCHAR(40) PRIMARY KEY,
    project_id   UUID NOT NULL REFERENCES catalog.intake_project (project_id),
    description  TEXT NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at    TIMESTAMPTZ,
    CONSTRAINT ck_intake_change_status CHECK (status IN ('OPEN', 'COMPLETED', 'DISCARDED'))
);

INSERT INTO catalog.intake_change (request_id, project_id, description, status, created_at, closed_at)
SELECT started.request_id,
       project.project_id,
       coalesce(nullif(started.description, ''), nullif(started.detail, ''), 'Change request'),
       CASE
           WHEN EXISTS (
               SELECT 1
                 FROM catalog.platform_activity later
                WHERE later.request_id = started.request_id
                  AND later.action <> 'CHANGE_STARTED'
           ) THEN 'COMPLETED'
           ELSE 'OPEN'
       END,
       started.created_at,
       CASE
           WHEN EXISTS (
               SELECT 1
                 FROM catalog.platform_activity later
                WHERE later.request_id = started.request_id
                  AND later.action <> 'CHANGE_STARTED'
           ) THEN started.created_at
           ELSE NULL
       END
  FROM catalog.platform_activity started
  JOIN catalog.intake_project project
    ON project.project_name = started.object_name
   AND lower(project.tech_owner_email) = lower(started.user_email)
 WHERE started.action = 'CHANGE_STARTED'
   AND started.request_id IS NOT NULL
ON CONFLICT (request_id) DO NOTHING;

UPDATE catalog.intake_change change
   SET status = 'DISCARDED',
       closed_at = CURRENT_TIMESTAMP
 WHERE change.status = 'OPEN'
   AND change.request_id <> (
       SELECT newer.request_id
         FROM catalog.intake_change newer
        WHERE newer.project_id = change.project_id
          AND newer.status = 'OPEN'
        ORDER BY newer.created_at DESC
        LIMIT 1
   );

DELETE FROM catalog.platform_activity activity
 WHERE activity.action = 'CHANGE_STARTED'
   AND activity.request_id IN (
       SELECT change.request_id
         FROM catalog.intake_change change
        WHERE change.status = 'DISCARDED'
          AND NOT EXISTS (
              SELECT 1
                FROM catalog.platform_activity other
               WHERE other.request_id = change.request_id
                 AND other.action <> 'CHANGE_STARTED'
          )
   );

CREATE UNIQUE INDEX IF NOT EXISTS ux_intake_change_one_open
    ON catalog.intake_change (project_id)
 WHERE status = 'OPEN';
