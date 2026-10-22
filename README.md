# Online Data Platform

Local platform for discovering YugabyteDB metadata and for taking a database change from an intake form through SQL execution and catalog registration.

The catalog answers: which cloud, environment, universe, database, schema, and table exist, who owns the table, and what its columns, keys, and DDL are. API and Spark jobs use that catalog to decide where to read and write. The intake path is how a person adds a project, designs or uploads tables, and promotes them.

Java 21 and Maven build the services. The UI is React on Vite.

## Four APIs

Each API is its own Maven project and its own process. They share the Java package `com.yugabyte.metadata` and talk to each other with GraphQL. Product calls are `POST /graphql`. GraphiQL is `/graphiql`. `GET /api/v1/health` is an open liveness check and returns `{"status":"UP","api":"..."}`.

| Project | Port | Role |
| --- | --- | --- |
| `metadata-api` | 4000 | Catalog. Read metadata, and register a table after SQL has been applied. This is the API other teams and the MCP gateway call. It does not run intake SQL. |
| `login-api` | 4010 | Local sign-in only, so the UI can be tested end to end. The real platform will not use this service. |
| `intake-api` | 4020 | Intake design and orchestration. It stores the project, calls login, and asks execution to do the work. It does not run SQL. |
| `execution-api` | 4030 | The only service that runs DDL. After the database change succeeds, it tells the catalog to register the table. |

```
Browser :5173
  ├─ /graphql         → catalog   :4000
  ├─ /login/graphql   → login     :4010
  └─ /intake/graphql  → intake    :4020
                              │
                              ├─ login   :4010   signIn, ensureUser
                              └─ execution :4030  create database, schema, DDL, promote
                                          │
                                          └─ catalog :4000  registerTable, addCatalogColumn, addCatalogIndex

MCP client → metadata-mcp :4001 → catalog :4000
```

The Vite dev server adds the service credential. The browser bundle does not contain it.

Service-to-service calls, GraphQL, and GraphiQL use HTTP Basic `metadata-gateway` / `metadata-gateway`. A GraphQL failure is HTTP 200 with an `errors` array. A missing or wrong Basic credential is HTTP 401.

## Catalog

One catalog row is one copy of a table in one environment:

```
Cloud → Environment → Universe
                         ├─ Regions / nodes
                         └─ Database → Schema → Table → Columns / indexes / owner
                                              └─ Table context
```

A table in DEV and the same table in IT are two rows. If a search returns no row for an environment, that table is not in that environment.

| Table | What it stores |
| --- | --- |
| `cloud` | AWS, Azure |
| `environment` | DEV, IT, UAT, PROD |
| `universe` | Yugabyte universe, API endpoint, and YSQL configuration |
| `universe_region` | Regions in a universe |
| `universe_node` | Nodes and capacity |
| `data_owner` | Owner, contacts, and the platform user id |
| `database_metadata` | Database |
| `schema_metadata` | Schema |
| `table_context` | Business line, product line, system of origin, CAR id, storage, and TPS |
| `table_metadata` | Table definition, DDL, partition, and classification. Links to `table_context` |
| `column_metadata` | Columns |
| `index_metadata` | Indexes |
| `index_column_metadata` | Columns in each index |

`table_metadata.table_context_id` points at `table_context`. Several tables can share one context row.

The qualified name is built by the API. It is not a stored column:

`cloud.environment.universe.database.schema.table`

Example: `AWS.DEV.AWS-DEV-YB01.authz_team.authz.access_audit`.

The catalog query contract, including search filters and the table-definition document, is in [docs/catalog-api.md](docs/catalog-api.md). Database passwords are not returned.

Catalog writes (`registerTable`, `addCatalogColumn`, `addCatalogIndex`) use a separate admin connection (`yugabyte` / `yugabyte` locally) because the read pool is `metadata_reader`, which can only `SELECT`.

### Where the View popup gets its values

Opening **View** calls `tableById` and `tableDefinitionById`.

| Popup field | Source |
| --- | --- |
| Qualified name | Cloud, environment, universe, database name, schema name, and table name joined by the API |
| Owner | Intake **Project Name** |
| Owner id | Intake **Technical Owner** when that value is an email; otherwise the signed-in **User ID** |
| Primary contact | Intake **Technical Owner** |
| Secondary contact | Intake **Developer Contact** |
| Type | `BASE_TABLE`, the column default |
| Partitioned | True when the DDL primary key has a HASH column |
| Classification | `INTERNAL` for every intake registration, plus the intake **Business Line** |
| YSQL | The universe configuration, not the intake form. Sample universes are enabled on port 5433 |
| Partition | HASH columns parsed from the table DDL |
| DDL | The SQL stored when the table was registered |

## UI

`http://localhost:5173`. The shell is **Online Data Platform**. YugabyteDB is the working product. Data API, CDA, and Kafka are placeholders.

YugabyteDB has four tabs:

| Tab | What it does |
| --- | --- |
| Catalog | Search every table, or only tables owned by the signed-in user. One list shows the table and its key attributes. **View** opens the definition, columns, indexes, and DDL. The copy control on the DDL block copies the SQL. |
| Intake | File a project, create databases and schemas, then design a table or upload DDL. |
| Requests | Projects for the signed-in user. **User Inputs** shows the intake form. **Migrate** promotes tables. |
| Audit | Activity for the signed-in user. |

Sign in with a user id and a password of 8 to 72 characters. The first sign-in of a new user id creates the account. An existing id must match its password. The session is stored in the browser under `odp-session`.

### Intake form

A new project receives an **ODP Req ID** such as `ODP-6A8E8CE2`. That value is generated, shown read-only, and stored in `intake_project.edai_req_id`. It is not typed by the user. Change requests use a separate `REQ-` id.

The form fields, in order, are:

ODP Req ID, Business Line, Fund Type, Fund Value, Domain, Sub Domain, Project Name, Project Description, CAR ID, CAR Name, Technical Owner, Business Line Owner, Developer Contact, Support Assignment Group, Exp. Dev Date, Exp. IT Date, Exp. UAT Date, Exp. Prod Date.

Project Name is required. The signed-in user owns the project.

After submit:

1. Create a database. It is created in DEV. Locally the physical database is on this cluster.
2. Create a schema. The `public` schema is rejected.
3. Design a table, or upload DDL.

Allowed DDL is `CREATE SCHEMA`, `CREATE TABLE`, `CREATE INDEX`, and `ALTER TABLE ADD COLUMN`. `COMMENT` is allowed on the design and promote path. `DROP`, `TRUNCATE`, `DELETE`, `INSERT`, `UPDATE`, `GRANT`, `REVOKE`, `COPY`, `VACUUM`, and `CREATE DATABASE` / `ROLE` / `USER` are rejected. A column or statement that is the whole word `grant` is rejected by that rule. The CSV uploader splits on commas and does not handle quotes, so a description cannot contain a comma.

Intake sends the work to execution. Execution runs the SQL, then calls the catalog. The catalog stores the table under the matching universe (`AWS-DEV-YB01` for a local AWS DEV create, and the Azure equivalent when the cloud is Azure).

If the SQL fails, the error is the database error, the catalog is unchanged, and the script stays on the request. Run the same action again. There is no background retry.

If the SQL succeeds and catalog registration fails, the error is: “The database change was applied, but the catalog was not updated. Run the same action again.” Retry is safe. Creates use `IF NOT EXISTS`, and registration replaces that environment’s rows.

### Promote

Promotion is started from Requests, not from Intake. Choose IT, UAT, or PROD, and optionally a subset of tables. An empty selection promotes every table on the project.

Promotion does not overwrite other environments. Each environment keeps its own catalog copy. Locally, the physical database name gets a suffix: `name_it`, `name_uat`, or `name_prod`, on this same cluster. The catalog row is written to the universe for that cloud and environment, for example `AWS-IT-YB01`.

DDL and catalog registration are not one database transaction. If the second table fails, the first table may already be registered. Run the same promotion again.

## Login

`login-api` exists so this repository can exercise sign-in. A production deployment of the catalog, intake, and execution services does not depend on it.

`signIn(userId, password)` checks the account. `ensureUser(userId)` finds the user already established by sign-in and does not check the password. The user id is lowercase and matches `[a-z0-9][a-z0-9._-]{1,62}[a-z0-9]`. Passwords are stored with bcrypt. The session returns `id`, `userId`, and `subjectId` (`user:{loginId}`).

## MCP gateway

`metadata-mcp` on port 4001 exposes the catalog to an AI client over Streamable HTTP at `POST /mcp`. It has no JDBC driver and no database password. It calls catalog GraphQL with the `metadata-gateway` credential.

The MCP login is separate: HTTP Basic `metadata-reader` / `metadata-reader`.

Tools: `list_clouds`, `list_environments`, `list_universes`, `get_universe`, `list_databases`, `list_schemas`, `list_tables`, `get_table`, `get_table_definition`, `list_nodes`.

## Run locally

Start YugabyteDB, then load the schema. `scripts/bootstrap.sh` creates `yugabyte_metadata` and applies `sql/001` through `sql/013`. `sql/002_seed.sql` truncates the catalog tables, so run bootstrap only on a fresh database.

```bash
docker compose up -d
./scripts/bootstrap.sh
```

Java 21 is required. From each project directory:

```bash
cd metadata-api && mvn -q package -DskipTests && java -jar target/metadata-api-1.0.0.jar
cd login-api     && mvn -q package -DskipTests && java -jar target/login-api-1.0.0.jar
cd intake-api    && mvn -q package -DskipTests && java -jar target/intake-api-1.0.0.jar
cd execution-api && mvn -q package -DskipTests && java -jar target/execution-api-1.0.0.jar
cd metadata-mcp  && mvn -q package -DskipTests && java -jar target/metadata-mcp-1.0.0.jar
cd web           && npm install && npm run dev
```

To run the same APIs and the compiled UI on another machine without Maven or npm, use [RUN_WITHOUT_COMPILE.md](RUN_WITHOUT_COMPILE.md). The Demo button uses the sample dataset built into the UI.

| What | Where |
| --- | --- |
| YSQL | `localhost:5433`, database `yugabyte_metadata`, schema `catalog` |
| Read login | `metadata_reader` / `metadata_reader` (SELECT only) |
| Admin and bootstrap login | `yugabyte` / `yugabyte` |
| Master UI | `http://localhost:7001` (host port 7000 is used by macOS Control Center) |
| Catalog GraphQL | `http://localhost:4000/graphql` |
| Login GraphQL | `http://localhost:4010/graphql` |
| Intake GraphQL | `http://localhost:4020/graphql` |
| Execution GraphQL | `http://localhost:4030/graphql` |
| Service Basic auth | `metadata-gateway` / `metadata-gateway` |
| MCP | `POST http://localhost:4001/mcp`, Basic `metadata-reader` / `metadata-reader` |
| UI | `http://localhost:5173` |

These passwords are the local sample only. For any shared environment set `YB_METADATA_HOST`, `YB_METADATA_PORT`, `YB_METADATA_DATABASE`, `YB_METADATA_USERNAME`, `YB_METADATA_PASSWORD`, `YB_ADMIN_USERNAME`, `YB_ADMIN_PASSWORD`, `METADATA_API_USERNAME`, `METADATA_API_PASSWORD`, `MCP_USERNAME`, `MCP_PASSWORD`, `LOGIN_API_URL`, `EXECUTION_API_URL`, and `CATALOG_API_URL`.

Check that a process is up:

```bash
curl -s http://localhost:4000/api/v1/health
curl -s http://localhost:4010/api/v1/health
curl -s http://localhost:4020/api/v1/health
curl -s http://localhost:4030/api/v1/health
```

A catalog search:

```bash
curl -s -u metadata-gateway:metadata-gateway \
  -H 'Content-Type: application/json' \
  http://localhost:4000/graphql \
  -d '{"query":"query { result: searchTables(filter: {pageSize: \"5\"}) }"}'
```

## Sample data

Bootstrap loads 8 universes and 32 nodes: AWS and Azure, each with DEV, IT, UAT, and PROD. Universe codes look like `AWS-DEV-YB01` and `AZURE-PROD-YB01`. AWS stretch regions are `us-east-1` and `us-east-2`. Azure regions are `centralus` and `eastus`.

Logical samples `customerdb.customer.customer` and `finance.finance.invoice` are loaded on `AWS-PROD-YB01`.

`scripts/extract_catalog.py` reads a real Yugabyte universe from a connection file and prints `INSERT` statements for the catalog tables. It does not write the catalog. Passwords come from environment variables named in the file, not from the SQL. See `config/universes.example.json`.

```bash
YB_AWS_DEV_PASSWORD=secret python3 scripts/extract_catalog.py config/universes.example.json > catalog-load.sql
```

[docs/catalog-api.md](docs/catalog-api.md) is the catalog query reference. [docs/architecture.md](docs/architecture.md) and [docs/security.md](docs/security.md) describe the original catalog-and-MCP split. The four API projects above are the current layout.
