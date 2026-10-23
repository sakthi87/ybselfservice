# User flow

A person always starts at the same door. Demo stays in the browser. A real sign-in uses the login API, then the YugabyteDB tabs.

```mermaid
flowchart TD
  open[Open the page] --> choice{Demo or sign in}
  choice -->|Demo| demo[Sample data in this browser tab]
  choice -->|Sign in| id{User id already stored}
  id -->|No| create[Create the account with this password]
  id -->|Yes, no password yet| first[Save this password]
  id -->|Yes| match{Password matches}
  match -->|No| reject[Sign-in rejected]
  match -->|Yes| shell[Online Data Platform]
  create --> shell
  first --> shell
  demo --> shell
  shell --> product{Product}
  product -->|YugabyteDB| tabs[Catalog, Intake, Requests, Audit]
  product -->|Data API, CDA, or Kafka| placeholder[Placeholder screen]
```

## Catalog

Search is global, or limited to tables owned by the signed-in user.

```mermaid
flowchart TD
  catalog[Catalog] --> filters[Cloud, environment, universe, database, schema, table, owner]
  filters --> list[Table list]
  list --> view[View]
  view --> fields[Cloud, environment, universe, database, schema, table, owner, contacts]
  fields --> ddl[Columns, indexes, and SQL DDL]
  ddl --> copy[Copy DDL]
```

## Intake

A new project gets an ODP Req ID. An existing project continues under a `REQ-` change. One project can have only one open change.

```mermaid
flowchart TD
  intake[Intake] --> kind{New or existing project}
  kind -->|New| form[Project details. ODP Req ID is generated]
  form --> submit[Submit intake]
  submit --> dev[Create database in DEV]
  kind -->|Existing| pick[Open one of your projects]
  pick --> open{An open change already exists}
  open -->|Yes, no work yet| empty[Resume or delete it]
  open -->|Yes, work recorded| busy[Resume or finish it]
  open -->|No| describe[Describe the change and start it]
  empty --> dev
  busy --> dev
  describe --> dev
  dev --> schema[Create a schema. public is rejected]
  schema --> work{How to add the table}
  work -->|Design| design[Enter columns and keys]
  work -->|Upload DDL| upload[Preview, then execute]
  design --> done[Table is in DEV and in the catalog]
  upload --> done
  done --> finish[Finish change, or keep adding tables]
```

## When SQL or catalog registration fails

```mermaid
flowchart TD
  run[Execute or design] --> sql{SQL accepted}
  sql -->|No| stay[Database error. Catalog unchanged. Same script stays. Run the action again]
  sql -->|Yes| catalog{Catalog updated}
  catalog -->|No| partial[SQL is already applied. Run the same action again]
  catalog -->|Yes| saved[Table is registered in DEV]
```

## Requests

Migration starts here, not on Intake. The dialog opens with every table selected.

```mermaid
flowchart TD
  requests[Requests for this user] --> row[One project under its ODP Req ID]
  row --> inputs[User Inputs: read the intake form]
  row --> migrate[Migrate]
  migrate --> target[Choose IT, UAT, or PROD]
  target --> universe[Choose a universe in that environment]
  universe --> tables[Keep or clear the selected tables]
  tables --> go{Universe and at least one table}
  go -->|No| wait[Promote stays disabled]
  go -->|Yes| promote[Promote selected]
  promote --> names[Database name gains _it, _uat, or _prod]
  names --> copy[Catalog copy is written on the chosen universe]
  copy --> other[DEV and the other environments stay as they were]
```

## Audit

Every sign-in, intake action, and promotion for this user is listed here. A change request groups its database and table work under one `REQ-` id.
