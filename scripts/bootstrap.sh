#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PSQL="${PSQL:-/opt/homebrew/opt/libpq/bin/psql}"
HOST="${PGHOST:-localhost}"
PORT="${PGPORT:-5433}"
USER_NAME="${PGUSER:-yugabyte}"
DB_NAME="${PGDATABASE:-yugabyte_metadata}"

export PGPASSWORD="${PGPASSWORD:-yugabyte}"

echo "Waiting for YugabyteDB at ${HOST}:${PORT}..."
ready=0
for _ in $(seq 1 60); do
  if "$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d yugabyte -c "SELECT 1" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if PGPASSWORD= "$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d yugabyte -c "SELECT 1" >/dev/null 2>&1; then
    export PGPASSWORD=
    ready=1
    break
  fi
  sleep 3
done

if [[ "$ready" -ne 1 ]]; then
  echo "YugabyteDB did not accept connections on ${HOST}:${PORT}" >&2
  exit 1
fi

"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d yugabyte -v ON_ERROR_STOP=1 <<SQL
SELECT 'CREATE DATABASE ${DB_NAME}'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}')\\gexec
SQL

"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/001_schema.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/002_seed.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/003_reader.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/004_intake.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/005_intake_project_fields.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/006_activity.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/007_change_request.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/008_open_change.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/009_intake_id.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/010_login_subject.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/011_user_id_password.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/012_owner_email_optional.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/013_intake_form_fields.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/014_universe_connection.sql"
"$PSQL" -h "$HOST" -p "$PORT" -U "$USER_NAME" -d "$DB_NAME" -v ON_ERROR_STOP=1 -f "$ROOT/sql/015_entitlement.sql"

echo "Catalog loaded into ${DB_NAME}."
