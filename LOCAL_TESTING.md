# Local project layout and testing

This file is for someone who downloads the repo and wants to run it locally. Two Spring Boot processes are required. The Metadata API reads YugabyteDB. The MCP gateway does not.

Generated folders are omitted from the tree below: `web/node_modules`, `metadata-api/target`, and `metadata-mcp/target`.

## Repository tree

```
YugabyteMetadata/
├── docker-compose.yml          # Local YugabyteDB (YSQL on host port 5433)
├── README.md
├── LOCAL_TESTING.md
├── .gitignore
├── docs/
│   ├── architecture.md         # API and MCP gateway split
│   └── security.md             # Credentials and the database boundary
├── scripts/
│   └── bootstrap.sh            # Creates the database, schema, sample data, and reader role
├── sql/
│   ├── 001_schema.sql          # catalog schema, tables, views, roles
│   ├── 002_seed.sql            # Clouds, environments, universes, nodes, and two sample tables
│   └── 003_reader.sql          # metadata_reader login, SELECT only
├── metadata-api/               # Java 21 / Spring Boot REST API (port 4000)
│   ├── pom.xml
│   └── src/main/
│       ├── resources/
│       │   └── application.yml
│       └── java/com/yugabyte/metadata/
│           ├── MetadataApiApplication.java
│           ├── api/            # REST controllers
│           ├── metadata/
│           │   └── MetadataService.java
│           ├── catalog/        # JDBC queries used by MetadataService
│           ├── security/
│           │   └── SecurityConfig.java
│           └── web/            # UI search, nodes, and id-based routes
├── metadata-mcp/               # MCP gateway (port 4001). No database driver or credentials
│   ├── pom.xml
│   └── src/main/
│       ├── resources/
│       │   └── application.yml
│       └── java/com/yugabyte/metadata/mcp/
│           ├── MetadataMcpApplication.java
│           ├── client/
│           │   └── MetadataApiClient.java
│           ├── mcp/
│           │   ├── MetadataMcpTools.java
│           │   └── MetadataMcpResources.java
│           └── security/
│               └── SecurityConfig.java
├── web/                        # React UI (port 5173)
│   ├── package.json
│   ├── vite.config.js          # Proxies /api to the Metadata API and adds its service login
│   ├── index.html
│   └── src/
│       ├── main.jsx
│       ├── App.jsx
│       ├── api.js
│       ├── styles.css
│       └── components/
│           ├── TableCatalog.jsx
│           └── NodeCatalog.jsx
```

Run `metadata-api` on port 4000.

## What you need

- Docker
- Java 21
- Maven
- Node.js 18 or newer, for the UI
- `psql` (PostgreSQL client). The bootstrap script looks for Homebrew `libpq` at `/opt/homebrew/opt/libpq/bin/psql`. If `psql` is somewhere else, set `PSQL` to that path.

## Start the stack

From the repository root, in four terminals:

```bash
docker compose up -d
./scripts/bootstrap.sh
```

```bash
cd metadata-api
mvn spring-boot:run
```

```bash
cd metadata-mcp
mvn spring-boot:run
```

```bash
cd web
npm install
npm run dev
```

| Service | Address | Login |
| --- | --- | --- |
| YSQL | `localhost:5433`, database `yugabyte_metadata`, schema `catalog` | Bootstrap: `yugabyte` / `yugabyte`. API process: `metadata_reader` / `metadata_reader` (SELECT only) |
| Yugabyte master UI | http://localhost:7001 | Host port 7000 is left free because macOS often uses it |
| Metadata API | http://localhost:4000 | HTTP Basic `metadata-gateway` / `metadata-gateway`, except `GET /api/v1/health` |
| MCP gateway | http://localhost:4001 | HTTP Basic `metadata-reader` / `metadata-reader` on `POST /mcp` |
| React UI | http://localhost:5173 | None. Restart `npm run dev` after changing the API password |

Sample data is 2 clouds, 8 universes, and 32 nodes. Logical tables exist only on `AWS-PROD-YB01`: `customerdb.customer.customer` (owner `DOWNER-001`) and `finance.finance.invoice` (owner `DOWNER-002`). AWS stretch regions are `us-east-1` and `us-east-2`. Azure regions are `centralus` and `eastus`.

The MCP gateway configuration has no `spring.datasource`. Its only upstream is `METADATA_API_URL` (default `http://localhost:4000`) with the `metadata-gateway` service login.

## Test the UI

Open http://localhost:5173.

Table metadata:

- Cloud, environment, and universe dropdowns list AWS and Azure, and all 8 universes.
- The table list shows `customer` and `invoice`.
- Open `customer`. The page shows owner `Customer Data Platform` / `DOWNER-001`, four columns, and two indexes.
- The page does not show table size or row count.

Node metadata:

- The node list shows 32 nodes.
- Capacity totals are 416 CPU cores, 1,664 GiB memory, and 8,120 / 26,000 GiB storage.
- The first AWS DEV node is `yb-aws-dev-yb01-01` at `10.1.10.1`.

## Test REST

`GET /api/v1/health` is open. Every other API route requires the service login. A call without it returns 401.

```bash
curl -sS http://localhost:4000/api/v1/health

curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:4000/api/v1/clouds

curl -sS -u metadata-gateway:metadata-gateway http://localhost:4000/api/v1/clouds

curl -sS -u metadata-gateway:metadata-gateway \
  "http://localhost:4000/api/v1/universes?cloud=AWS&environment=PROD"

curl -sS -u metadata-gateway:metadata-gateway \
  http://localhost:4000/api/v1/universes/AWS-PROD-YB01/databases/customerdb/schemas/customer/tables/customer

curl -sS -u metadata-gateway:metadata-gateway \
  http://localhost:4000/api/v1/universes/AWS-PROD-YB01/databases/customerdb/schemas/customer/tables/customer/definition
```

Expected results:

- Health returns `"status":"ok"` and `"database":"yugabyte_metadata"`.
- Clouds without a login return `401`. With the service login, clouds are `AWS` and `AZURE`.
- The AWS PROD universe list contains `AWS-PROD-YB01`.
- The customer document has `"table":"customer"`, owner id `DOWNER-001`, primary contact `John Smith <john.smith@example.com>`, and `"lastUpdated":"2026-09-28T16:39:40.510Z"`.
- The definition document starts with `CREATE TABLE customer.customer` and includes a `PRIMARY_KEY` constraint. It does not include `rowCount` or `sizeBytes`.

A missing table returns 404:

```bash
curl -sS -u metadata-gateway:metadata-gateway -w '\nHTTP %{http_code}\n' \
  http://localhost:4000/api/v1/universes/AWS-PROD-YB01/databases/customerdb/schemas/customer/tables/missing
```

Other useful checks:

```bash
curl -sS -u metadata-gateway:metadata-gateway "http://localhost:4000/api/v1/tables?q=Platform&pageSize=50"
curl -sS -u metadata-gateway:metadata-gateway "http://localhost:4000/api/v1/nodes?cloud=AZURE&environment=UAT&pageSize=100"
curl -sS -u metadata-gateway:metadata-gateway http://localhost:4000/api/v1/catalog/options
```

Search for `Platform` returns `customer` and `invoice`. Azure UAT returns 5 nodes. The first private IP is `10.2.30.1`. Catalog options lists 8 universes.

## Test MCP

MCP is a separate process on `POST http://localhost:4001/mcp`. It uses Streamable HTTP and requires HTTP Basic. A call without credentials returns 401:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:4001/mcp
```

Start a session and copy the `Mcp-Session-Id` response header into `SESSION`:

```bash
curl -sS -D - -u metadata-reader:metadata-reader \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"manual","version":"1.0"}}}' \
  http://localhost:4001/mcp
```

The initialize result names the server `yugabyte-metadata` and enables tools and resources. Prompts are off.

```bash
SESSION='paste-the-session-id'

curl -sS -m 5 -N -u metadata-reader:metadata-reader \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  http://localhost:4001/mcp

curl -sS -m 8 -N -u metadata-reader:metadata-reader \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  http://localhost:4001/mcp
```

`tools/list` is returned as a server-sent event. The `data:` line lists these tools, each with `"readOnlyHint":true`:

- `list_clouds`
- `list_environments`
- `list_universes`
- `get_universe`
- `list_databases`
- `list_schemas`
- `list_tables`
- `get_table`
- `get_table_definition`
- `list_nodes`

`-m 8` ends `curl` because the MCP stream stays open.

Call the same customer lookup used by REST:

```bash
curl -sS -m 8 -N -u metadata-reader:metadata-reader \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_table","arguments":{"universe":"AWS-PROD-YB01","database":"customerdb","schema":"customer","table":"customer"}}}' \
  http://localhost:4001/mcp
```

The text inside the tool result is the same customer JSON returned by the Metadata API. The gateway loads it with `GET /api/v1/universes/AWS-PROD-YB01/databases/customerdb/schemas/customer/tables/customer`.
