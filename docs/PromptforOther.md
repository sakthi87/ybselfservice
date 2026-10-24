You are working in the odp-web-application-basecode-develop codebase. A second codebase, the YugabyteDB metadata app, lives at:

/ABSOLUTE/PATH/TO/YugabyteMetadata

Read both codebases before you change anything. Merge the YugabyteDB product features into this application under the existing DataStore tab. The finished DataStore experience must look, read, and be written like odp-web-application-basecode-develop. Do not make DataStore look like the standalone Yugabyte app.

## What to study first

1. In odp-web-application-basecode-develop, find the DataStore tab and how the other product tabs are built: routing, layout, navigation, shared components, forms, tables, dialogs, buttons, empty and error states, HTTP client, auth, and the signed-in user. Note the language, file layout, naming, state management, and styling system. Follow those. If this app is not React, rewrite the Yugabyte screens into this app’s language and component system. Do not embed a second React/Vite app inside this repo.

2. In the Yugabyte repo, the UI to port is only web/src. The shell in web/src/App.jsx is not part of the merge. Ignore its header, product list (YugabyteDB, Data API, CDA, Kafka), login page, and placeholder products. This host app already owns navigation and sign-in. web/src/components/NodeCatalog.jsx is unused. Do not port it.

3. Do not port the Java services into the web app. metadata-api, login-api, intake-api, execution-api, and metadata-mcp stay separate backends. The browser calls catalog and intake only. It never calls execution-api (port 4030) or metadata-mcp (port 4001).

4. docs/entitlementdesign.md in the Yugabyte repo is an unfinished draft. Ignore it. The implemented screens and API calls are the behavior to preserve.

## Where it goes

Add YugabyteDB inside the existing DataStore tab, using this app’s tab or sub-navigation pattern. The five sections, in this order, are:

- Catalog
- Entitlements
- Intake
- Requests
- Audit

Keep the host app’s look and feel: its type, color, spacing, tables, forms, dialogs, and buttons. Restyle the Yugabyte screens to match. Do not copy web/src/styles.css, and do not restyle the rest of this application.

## Identity

Use the user who is already signed in to odp-web-application-basecode-develop. Do not add the Yugabyte login screen.

The Yugabyte APIs record work against a user id string (examples: demo.user, alex.chen). Map the host user’s stable id into that userId and pass it to Intake, Requests, Audit, and Entitlements “Mine”. Do not ask the user to type their id on the intake form.

If this app has no signed-in user yet, stop and say what identity field you need. Do not invent a second login.

## API calls

The Yugabyte UI uses GraphQL POST { query, variables } and reads data.result. Operation names are the field after result:.

- Catalog, including entitlements: POST /graphql. This is metadata-api on port 4000. Client: web/src/api.js.
- Intake, Requests, and Audit: POST /intake/graphql. This is intake-api on port 4020. The Vite proxy strips the /intake prefix before the jar, whose own path is /graphql.
- Login: POST /login/graphql. Do not call this if the host app already has a session.

The browser must not contain the gateway username or password. Today Vite and scripts/serve-ui.py add Authorization: Basic for metadata-gateway. Put that credential in this app’s existing server-side proxy or BFF, not in frontend source. If this app has no proxy, add the smallest server-side forward for these three paths and document it. Do not put Basic auth in the JavaScript bundle.

Make the three paths configurable. This app may later mount them under a prefix such as /yugabyte/graphql, /yugabyte/login/graphql, and /yugabyte/intake/graphql. Default to the paths above unless this codebase already has a different API prefix convention. Match that convention.

Catalog reads return a JSON scalar, so the query is result: searchTables(filter: $filter) with no subfields. Keep that shape. Requests and Intake use the same result alias.

## Catalog

Source: web/src/components/TableCatalog.jsx and web/src/api.js.

One table list. Filters: Cloud, Environment, Universe, Database, Schema, Table, Owner, CAR ID, and a free-text search. Database, schema, and table options narrow from the selection above them. CAR ID and free text apply when Search is clicked, not on each keystroke. CAR ID is a case-insensitive exact match.

Two scopes: Explore (every table) and Owned by me (tables whose owner user id is the signed-in user).

Each row has View. The popup shows only:

- Cloud, Environment, Universe, Database, Schema, Table
- Entitlement code and name
- Owner name and owner id
- Primary contact, Secondary contact
- Columns, Indexes
- SQL DDL and a copy control

Do not show Type, Classification, YSQL port, Partition, or DDL as JSON. The path above the title stays cloud / environment / universe / database / schema.

## Entitlements

Source: web/src/components/Entitlements.jsx.

Display only. People request access in another portal. Do not build a request, approve, or grant flow.

The list columns are Cloud, Environment, Universe, Database, Data owner, Entitlement, and View.

- Mine: entitlements where the signed-in userId is a member. Query entitlements(userId).
- All databases: entitlements(userId: null). A database with no entitlement shows a dash and View stays disabled.
- View calls entitlement(entitlementId) and lists user id, name, email, and association (OWNER or MEMBER). Highlight the signed-in user.

On a catalog table, the entitlement is the one attached to that table’s database. Tables in the same database show the same entitlement.

## Intake

Sources: web/src/components/IntakeFlow.jsx and web/src/components/DesignTable.jsx. Both call /intake/graphql.

Keep this flow:

- New project request, or existing project request.
- New project: ODP Req ID is generated, read-only, format ODP- plus 8 uppercase letters or digits. It is not typed by the user. The server accepts ODP-[A-Z0-9]{8} when unused and otherwise generates one. Stored as edai_req_id. Do not show a second generated intake id.
- Project fields, in the current two-column form: ODP Req ID, Business Line, Fund Type (capital, operational, other), Fund Value, Domain, Sub Domain, Project Name (required), Project Description, CAR ID, CAR Name, Technical Owner, Business Line Owner, Developer Contact, Support Assignment Group, Exp. Dev Date, Exp. IT Date, Exp. UAT Date, Exp. Prod Date.
- Existing project: list ODP Req ID, project, domain, business line, and Open.
- One open change per project. If one exists, resume it, finish it when it has work, or delete it when it has no work. A new change needs a description. Its id is a REQ- id. ODP Req ID and REQ- id are different.
- Then create database, create schema, design a table, upload DDL, add a column, and add an index.
- Design supports the entity form, CSV upload, model generation, and create. CSV splits on commas and does not handle quotes. Descriptions must not contain commas.
- Upload DDL has preview and execute.
- Promotion is not on Intake. It stays on Requests.
- A new project registration is recorded as submitted. It does not create an intake change row by itself.

Preserve these failure messages:

- If DDL fails, show the database error. The catalog is unchanged. The script remains on the request. Retry is the same Execute or Migrate action. There is no background retry.
- If DDL succeeds and catalog registration fails, show: “The database change was applied, but the catalog was not updated. Run the same action again.” Retry is safe.
- A failed promotion does not change other environments.

## Requests

Source: web/src/components/Requests.jsx. Calls /intake/graphql.

List the signed-in user’s projects. Group and label them by ODP Req ID (edai_req_id). CAR ID is not the group key. CAR ID and CAR Name appear in User Inputs.

User Inputs opens the submitted project fields.

Migrate opens the promotion dialog:

- Environment is IT, UAT, or PROD.
- Universe lists only universes in that environment, labeled code and cloud. If exactly one is active, preselect it.
- Opening Migrate selects all tables. The user can change the selection.
- Promote stays disabled until a universe and at least one table are selected.
- The request body is environment, universeCode, and tableIds. Do not send a hardcoded cloud. The selected universe supplies cloud and environment.
- Show the returned environment, cloud, universeCode, and promotions, including each target database. Target names are the source name plus _it, _uat, or _prod.

## Audit

Source: web/src/components/AuditLog.jsx. Calls intake activity for the signed-in userId, with the existing request, action, and text filters. Show the returned history in this app’s table pattern.

## Demo

Port Demo only inside DataStore, and only if it does not replace this app’s sign-in.

Behavior to keep: a Demo control enters an in-browser sample dataset. While Demo is on, every Catalog, Entitlements, Intake, Requests, and Audit call uses that dataset and must not fetch /graphql or /intake/graphql. Mutations update only the in-browser dataset. Exit Demo returns to the real signed-in session. Demo data starts in web/src/demo/fixture.js. The responder is web/src/demo/respond.js. Session keys are odp-demo and odp-demo-data. A new browser tab is not in Demo.

Sample data includes customer and invoice on AWS PROD, account and reader_session on AWS DEV owned by demo.user, and role on Azure DEV. Customer Hub is ODP-DEMO1001 with CAR-4401. Payments is ODP-DEMO2002 with CAR-4402. Entitlements: ENT-CUSTOMER-HUB, ENT-CUSTOMER-HUB-DEV, ENT-GQL-FLOW include demo.user. ENT-AUTHZ does not, so Mine and All differ.

If this app cannot host that isolated mode cleanly, skip Demo and say so. Do not half-wire it so Demo still calls the live APIs.

## Do not change

- Do not change Java API code, SQL, or the Yugabyte repo unless a host-app path forces a one-line client change. This merge happens in odp-web-application-basecode-develop.
- Do not copy gateway credentials, universe connection passwords, or application.yml secrets into the frontend.
- Do not add an access-request workflow for entitlements.
- Do not recreate the standalone Online Data Platform shell.
- Do not commit unless I ask.

## How to implement

Before editing, write a short mapping: each Yugabyte section, the host files you will add or change, which host components you will reuse, and where the signed-in user id comes from. Then implement.

Match this repo’s formatting, naming, imports, and folder layout. Reuse its table, form, modal, button, and error components. Where the Yugabyte screen uses a behavior this app does not have, add the smallest local piece that still looks native.

After implementation, run this app and walk DataStore as a signed-in user, or Demo if you ported it:

- Catalog search, CAR ID filter, Owned by me, and View, including Entitlement and DDL copy.
- Entitlements Mine, All databases, and View people.
- Intake new project with a generated ODP Req ID, then an existing project and one open change.
- Requests User Inputs and Migrate, including universe required and at least one table.
- Audit for the same user.
- Confirm a Demo session makes no GraphQL calls, if Demo was ported.
- Confirm the surrounding host pages you did not mean to change still render.

Fix what that walkthrough breaks before you finish. In the summary, list the files changed, how API paths and the user id are wired, what you intentionally did not port, and anything you could not verify.