-- YSQL endpoint for each universe. Execution reads this at promote time.
-- The password is not stored here. secret_ref names the environment variable
-- that holds it. Local rows point at the one test cluster.

CREATE TABLE IF NOT EXISTS catalog.universe_connection (
    universe_id     UUID PRIMARY KEY,
    ysql_host       VARCHAR(500) NOT NULL,
    ysql_port       INTEGER NOT NULL DEFAULT 5433,
    admin_database  VARCHAR(100) NOT NULL DEFAULT 'yugabyte',
    auth_mode       VARCHAR(20) NOT NULL,
    username        VARCHAR(200) NOT NULL,
    secret_ref      VARCHAR(500),
    ssl_mode        VARCHAR(30) NOT NULL DEFAULT 'disable',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      VARCHAR(100) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(100) NOT NULL,
    CONSTRAINT fk_universe_connection_universe
        FOREIGN KEY (universe_id) REFERENCES catalog.universe(universe_id) ON DELETE CASCADE,
    CONSTRAINT ck_universe_connection_auth CHECK (auth_mode IN ('LDAP', 'CERT', 'PASSWORD')),
    CONSTRAINT ck_universe_connection_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_universe_connection_port CHECK (ysql_port BETWEEN 1 AND 65535)
);

INSERT INTO catalog.universe_connection (
    universe_id, ysql_host, ysql_port, admin_database, auth_mode, username, secret_ref, ssl_mode, status, created_by, updated_by
)
SELECT universe_id, 'localhost', 5433, 'yugabyte', 'PASSWORD', 'yugabyte', 'YB_ADMIN_PASSWORD', 'disable', 'ACTIVE', 'bootstrap', 'bootstrap'
  FROM catalog.universe
 WHERE universe_code IN (
    'AWS-DEV-YB01', 'AWS-IT-YB01', 'AWS-UAT-YB01', 'AWS-PROD-YB01',
    'AZURE-DEV-YB01', 'AZURE-IT-YB01', 'AZURE-UAT-YB01', 'AZURE-PROD-YB01'
 )
ON CONFLICT (universe_id) DO NOTHING;

REVOKE ALL ON catalog.universe_connection FROM metadata_read;
REVOKE ALL ON catalog.universe_connection FROM metadata_write;
