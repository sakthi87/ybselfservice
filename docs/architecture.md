# Architecture

Phase 1 runs two stateless Spring Boot services. The Metadata API is the only process that can reach YugabyteDB. The MCP gateway has no JDBC driver, database URL, or database password. It calls the Metadata API over HTTP.

```
AI client
  │  MCP / HTTP Basic
  ▼
MCP Gateway :4001
  │  HTTP Basic service credential
  ▼
Metadata API :4000
  │  metadata_reader, SELECT only
  ▼
YugabyteDB
```

The React UI uses the Metadata API through the Vite proxy. The proxy adds the API service credential so the browser bundle does not contain it.

```
React :5173
  ▼
Metadata API :4000
  ▼
YugabyteDB
```

Both consumers receive the same JSON. Controllers call `MetadataService`, which calls `CatalogService`. The gateway tools call `MetadataApiClient` and return that JSON.

Local ports:

| Process | Port | Credentials it holds |
| --- | --- | --- |
| Metadata API | 4000 | Database `metadata_reader`, API user `metadata-gateway` |
| MCP Gateway | 4001 | MCP user `metadata-reader`, API user `metadata-gateway` |
| React | 5173 | None. The dev proxy adds the API credential |
