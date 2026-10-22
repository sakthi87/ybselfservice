# Security boundary

The MCP gateway is the AI-facing process. It must not be able to open a database session.

The gateway project does not depend on JDBC or the PostgreSQL driver. Its configuration has `metadata.api.base-url` and a service password for the Metadata API. It does not have `spring.datasource`.

The Metadata API is the only service with:

- `jdbc:postgresql://.../yugabyte_metadata`
- username `metadata_reader`
- a Hikari pool
- `SET default_transaction_read_only = on` on every connection

`sql/003_reader.sql` grants `metadata_reader` the `metadata_read` role and turns on `default_transaction_read_only`. That role can `SELECT` catalog tables. It cannot insert, update, or delete.

## Two logins

| Caller | Login | What it can do |
| --- | --- | --- |
| AI client → MCP Gateway | `metadata-reader` / `metadata-reader` | Call `POST /mcp` |
| MCP Gateway → Metadata API | `metadata-gateway` / `metadata-gateway` | Call `GET /api/**` |
| Database | `metadata_reader` / `metadata_reader` | `SELECT` in schema `catalog` |

`GET /api/v1/health` is open so a process check does not need the service password. Every other API route requires the service login. `POST /mcp` requires the MCP login. Spring AI does not authenticate the MCP endpoint; `SecurityConfig` in `metadata-mcp` does.

A caller that passes MCP authentication is not trusted by the Metadata API. The gateway presents its own service credential, and the API checks that credential again.

## Local defaults

These passwords are the local sample only. Set `METADATA_API_USERNAME`, `METADATA_API_PASSWORD`, `MCP_USERNAME`, `MCP_PASSWORD`, `YB_METADATA_USERNAME`, and `YB_METADATA_PASSWORD` outside this repository for any shared environment.

Production should use HTTPS with mTLS or OAuth2 between the gateway and the API, and a network policy that denies the gateway any route to YugabyteDB. Knowing no password is not a substitute for that network denial.
