# Catalog API

The Catalog API is a Spring Boot GraphQL API. API services and Spark jobs use it to find a YugabyteDB table, read its columns and keys, and learn which universe and database to open. It does not run SQL and it does not return database passwords.

Local endpoint: `POST http://localhost:4000/graphql`

GraphiQL: `http://localhost:4000/graphiql`

Every request uses HTTP Basic authentication with the catalog service credential. Locally that user is `metadata-gateway` / `metadata-gateway`. The platform sign-in user id is not a catalog credential.

`GET /api/v1/health` is only a process check. It does not require a credential and returns `{"status":"UP","api":"catalog"}`. Product calls go through GraphQL.

## Request

```bash
curl -s -u metadata-gateway:metadata-gateway \
  -H 'Content-Type: application/json' \
  http://localhost:4000/graphql \
  -d '{"query":"query { health { status api } }"}'
```

Send `query` and, when the operation has arguments, `variables`. A successful body is `{"data": ...}`. A failed body is `{"errors":[{"message":"..."}]}` with HTTP 200. A missing or wrong Basic credential returns HTTP 401.

| Error | Meaning |
| --- | --- |
| `BAD_REQUEST` | A query value is not valid |
| `NOT_FOUND` | The cloud, universe, database, schema, table, or node does not exist |
| HTTP 401 | The Basic credential is missing or wrong |

## What a caller receives

Metadata is stored once per cloud and environment:

`cloud → environment → universe → database → schema → table`

A table that exists in DEV and IT is two catalog rows. They can differ. If a search returns no row for an environment, that table is not in that environment.

Each table document includes `qualifiedName`, for example `AWS.DEV.AWS-DEV-YB01.authz_team.authz.role`. The table definition also includes `target.apiEndpoint` and `target.ysql.port`. Use those with the column list to build a reader or writer. Database passwords are issued separately and are not in this API.

## Queries

| Query | Purpose |
| --- | --- |
| `clouds` | Clouds |
| `environments(cloud)` | Environments in a cloud |
| `universes(cloud, environment)` | Universe summaries |
| `universe(id)` | One universe, regions, nodes, capacity, `apiEndpoint`, and configuration. `id` may be a code or a UUID |
| `regions(universe)` | Regions for a universe |
| `universeNodes(universe)` | Nodes for a universe |
| `databases(universe)` | Databases. Each has `databaseId` and `databaseName` |
| `schemas(universe, database)` | Schemas by name |
| `schemasById(databaseId)` | Schemas by catalog id |
| `tables(universe, database, schema)` | Tables in a schema |
| `tablesBySchema(schemaId)` | Tables by schema id |
| `table(universe, database, schema, name)` | Table summary: owner, columns, indexes, classification |
| `tableById(tableId)` | The same summary by catalog id |
| `tableDefinition(universe, database, schema, name)` | DDL, partitioning, columns, indexes, constraints, and `target` |
| `tableDefinitionById(tableId)` | The same definition by catalog id |
| `searchTables(filter)` | Paged search. Returns `items` and `page` |
| `searchNodes(filter)` | Paged nodes plus `capacity` |
| `node(nodeId)` | One node |
| `owners` | Data owners |
| `catalogOptions` | Values for the catalog filters |

`searchTables` accepts `pageSize`, `pageToken`, `page`, `cloud`, `environment`, `universe`, `database`, `schema`, `owner`, `ownerEmail`, `ownerUserId`, `table`, `tableName`, `q`, and `status`. `pageSize` defaults to 25 and is capped at 200. Pass `page.nextPageToken` back as `pageToken`. `page` may also be a page number starting at 1.

`searchNodes` accepts `pageSize`, `pageToken`, `page`, `cloud`, `environment`, `universe`, `region`, `role`, `status`, and `nodeName`.

## Where to connect

1. Search or walk the hierarchy until you have the universe, database, schema, and table for the environment you will read or write.
2. Call `tableDefinition` and read `target.qualifiedName`, `target.apiEndpoint`, and `target.ysql.port`.
3. Call `universe` for regions and nodes. A node has `hostName`, `privateIp`, `publicIp`, `role`, `masterPort`, and `tserverPort`.
4. Open YSQL on `target.ysql.port` (5433 in the sample universes) against `database`. Use the column names and types from `columns`, and honor `primaryKey` and `partitioning` when writing.

Do not point a job at DEV metadata and then write to IT. Select the environment you intend to change.

## Sample

```bash
AUTH='metadata-gateway:metadata-gateway'
curl -s -u "$AUTH" -H 'Content-Type: application/json' http://localhost:4000/graphql -d '{
  "query": "query($filter: TableSearch) { result: searchTables(filter: $filter) }",
  "variables": { "filter": { "cloud": "AWS", "environment": "DEV", "database": "authz_team", "schema": "authz", "table": "role" } }
}'
```

```bash
curl -s -u "$AUTH" -H 'Content-Type: application/json' http://localhost:4000/graphql -d '{
  "query": "query($tableId: ID!) { result: tableDefinitionById(tableId: $tableId) }",
  "variables": { "tableId": "TABLE_ID" }
}'
```

This catalog is the `metadata-api` project. Login, intake, and execution are separate projects and are not part of this API. Locally they are GraphQL at `http://localhost:4010/graphql`, `http://localhost:4020/graphql`, and `http://localhost:4030/graphql`. Each has GraphiQL at `/graphiql`.
