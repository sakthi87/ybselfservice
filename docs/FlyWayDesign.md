# Designing a centralized Flyway REST wrapper

The goal is to build a centralized Spring Boot service running in Kubernetes that lets authorized application teams execute and track database migrations through REST APIs, without needing to install Flyway on their own VMs.

The key design principle is: GitLab stores the migration scripts, your wrapper controls their execution, and Flyway tracks the applied migrations in each database.

## 1. Overall architecture

Application teams / Deployment orchestrator

Request migrations, check status, review results

HTTPS / REST API

## Centralized Flyway Wrapper — Spring Boot on Kubernetes

API security

Authentication, authorization

GitLab integration

Approved migration artifacts

Execution manager

Validate, migrate, coordinate jobs

Audit and job tracking

Who ran what, when, and outcome

GitLab

Versioned SQL migrations and release artifacts

Target databases

Schema changes and Flyway history table

Conceptual architecture. GitLab and databases are external dependencies; the wrapper runs in Kubernetes.

You do not need a standalone Flyway VM. Package Flyway as a dependency inside your Spring Boot application. For stronger isolation and resource control, the wrapper can launch migration jobs as separate Kubernetes Jobs later, but that is not mandatory for the first version.

## 2. User experience and underlying technical flow

Imagine an application team wants to deploy database changes from Dev to QA and then Production.

The team should not need to understand Flyway internals. They should select an application, release, environment, and operation.

## Step-by-step experience

1. Developer commits migration scripts

User experience

The developer adds `V12__add_customer_status.sql` to the application's GitLab repository and submits a merge request.

Underlying technical flow

GitLab reviews the change, runs SQL checks and tests, and creates an approved, versioned migration artifact.

2. Team selects a release

User experience

In a portal or deployment pipeline, the user selects the application and release containing migration `V12`.

Underlying technical flow

The orchestration system passes the approved artifact identifier, commit or release version, and target environment to the wrapper. The wrapper verifies that the artifact is trusted.

3. Team validates the deployment

User experience

The team requests a status check or validation before execution. The system reports pending migrations and validation errors.

Underlying technical flow

The wrapper retrieves the exact scripts, configures Flyway for the selected database, and runs `validate` and `info`. Flyway compares available migrations with its history table.

Product Documentation

+1

4. Team requests execution

User experience

An authorized user or automated pipeline clicks Deploy or invokes the execute API. Production execution can require approval.

Underlying technical flow

The wrapper authorizes the request, creates a job record, acquires an execution lock, and invokes Flyway's `migrate` operation. Flyway applies eligible pending scripts in version order and records the results.

Product Documentation

+1

5. Team reviews the result

User experience

The user sees success or failure, the migration versions processed, execution time, and any actionable errors.

Underlying technical flow

The wrapper updates its job and audit records. The database's Flyway history table remains the source of truth for applied migration history; the wrapper's own records track API requests and orchestration outcomes.

## 3. Decide how GitLab provides migration scripts

There are two viable approaches, but I recommend the first for a centralized enterprise service.

RecommendedApproach A — GitLab produces an immutable migration artifact

- Developers commit scripts to GitLab.
- GitLab CI validates them and packages them into a versioned artifact.
- The artifact is stored in GitLab's package registry or another approved artifact store.
- The wrapper downloads the exact artifact selected for deployment.

This ensures QA and Production receive the same approved scripts. GitLab supports both pipeline artifacts and package registry workflows.

GitLab Docs

+1

Approach B — Wrapper downloads scripts directly from GitLab

The wrapper uses a restricted GitLab token to fetch a specified repository and immutable commit. This is simpler initially, but the wrapper now needs GitLab access, token management, and repository-fetch logic.

Avoid downloading from a moving branch such as `main` at execution time. The same release should always refer to the same migration contents.

## 4. REST API design

Start with a small set of endpoints. The examples below are proposed API contracts, not built-in Flyway endpoints.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v1/migration-jobs` | Request validation or execution |
| `GET /api/v1/migration-jobs/{jobId}` | Check job status and results |
| `GET /api/v1/migration-status` | View applied and pending migrations |
| `POST /api/v1/migration-validation` | Validate without applying changes |

For example, an execution request might contain:

json
```
{
  "application": "customer-service",
  "release": "2026.10.21.1",
  "environment": "QA",
  "operation": "MIGRATE"
}
```

The wrapper should resolve the database connection from a trusted application/environment configuration. Do not accept arbitrary JDBC URLs or passwords from API callers.

For long-running migrations, return `202 Accepted` with a `jobId`. The caller polls the job endpoint until it reaches `SUCCEEDED` or `FAILED`.

## 5. Spring Boot implementation structure

A maintainable project could use this package structure:

flyway-wrapper/

controller/

REST endpoints and request validation

service/

Migration orchestration and workflow rules

flyway/

Flyway configuration, validate, info and migrate

gitlab/

Artifact retrieval and integrity verification

config/

Environment registry, database settings and security

job/

Job lifecycle, execution status and persistence

audit/

Caller identity, approvals, actions and outcomes

exception/

Consistent API errors and failure handling

### Core dependencies

For a Maven-based Spring Boot project, you would typically need:

- `spring-boot-starter-web` — REST APIs.
- `spring-boot-starter-validation` — validate API request fields.
- `spring-boot-starter-security` — secure the wrapper.
- `spring-boot-starter-data-jpa` and a JDBC driver — persist wrapper job and audit records.
- Flyway's core dependency and the appropriate database-specific Flyway module and JDBC driver, according to the database and Flyway version.

Keep Flyway and its database driver versions compatible, pin them in your build, and test upgrades before deployment.

### How the wrapper invokes Flyway

Conceptually, the service creates a configured Flyway instance for a specific request:

java
```
Flyway flyway = Flyway.configure()
    .dataSource(dataSource)
    .locations("filesystem:" + migrationDirectory)
    .load();

// Inspect pending/applied migrations
MigrationInfoService info = flyway.info();

// Check migration consistency
flyway.validate();

// Apply pending migrations only when authorized
flyway.migrate();
```

This is illustrative Java code; production code must configure the correct environment-specific datasource, artifact location, schemas, and error handling. It must also avoid invoking migration execution automatically on every REST service startup.

Flyway's `info`, `validate`, and `migrate` operations serve different purposes: inspect status, verify consistency, and apply pending changes, respectively.

Product Documentation

+2

## 6. Where should job status and history live?

Use two separate kinds of records.

| Record | Stored where? | What it tells you |
| --- | --- | --- |
| Migration history | Target database, `flyway_schema_history` | Which migrations Flyway applied, their checksums, and their outcomes |
| Wrapper job history | A persistent service database | Who requested the operation, which release and environment were selected, job status, approval details, and errors |

Do not rely on in-memory job status: a Kubernetes pod can restart or be replaced while a migration is running.

A job record could contain:

- `job_id`
- `application_id`
- `release_id` and commit/artifact digest
- `environment`
- `operation`
- `requested_by`
- `approval_reference`
- `status`
- `started_at`, `completed_at`
- `result_summary` and sanitized error details

Treat the wrapper's history as an audit and workflow record, not as a replacement for Flyway's database history.

## 7. Kubernetes deployment design

For the first implementation, use these components:

- Spring Boot Deployment: hosts the REST API and request validation.
- Persistent job store: stores jobs and audit records independently of pod memory.
- Artifact access: retrieves approved migration packages from GitLab or an artifact registry.
- Secrets management: supplies database credentials and GitLab credentials through your approved secrets platform.
- Execution coordination: prevents conflicting jobs against the same target database, including requests that arrive at different replicas.
- Observability: central logs, metrics, tracing, and alerts for failed or prolonged migrations.

For production, I would separate API handling from migration execution using a durable queue or dedicated Kubernetes Jobs. The API accepts the request; a worker executes it. This reduces the risk of a REST request timing out or an API pod restart disrupting job tracking.

A database-level lock and a durable job state are important because two callers must not concurrently execute migrations against the same target. Flyway has migration coordination mechanisms, but the wrapper also needs to manage duplicate requests, authorization, and job lifecycle.

## 8. Security and production safeguards

Before allowing teams to use the service broadly, implement these controls:

- Authentication: integrate with your organization's identity provider, such as OAuth2/OIDC.
- Authorization: restrict which teams can migrate which applications and environments. Production should have stricter permissions.
- Approvals: enforce production approvals through your deployment workflow.
- Immutable releases: deploy the exact artifact that passed validation and approval.
- Database privileges: use dedicated migration credentials with only the required privileges.
- Failure handling: capture partial failures, expose actionable errors, and require deliberate recovery procedures. Do not blindly rerun failed migrations.
- Auditability: retain the caller, approval, release identifier, target environment, and execution result.
- Concurrency and retries: use idempotency keys or equivalent duplicate-request protection, plus distributed coordination for competing migration jobs.

Also plan how to onboard existing databases. If their schemas already exist but Flyway has never managed them, they may need a carefully reviewed baseline before normal migrations begin. Do not automatically baseline an existing database without verifying its actual schema state.

Product Documentation

+1

## 9. Recommended implementation phases

1. Phase 1 — Foundation: Create the Spring Boot service, add Flyway, configure one non-production database, and test `info`, `validate`, and `migrate`.
2. Phase 2 — GitLab integration: Establish migration naming rules, CI validation, immutable artifacts, and artifact retrieval.
3. Phase 3 — REST API: Implement job creation, status retrieval, validation, execution, error handling, and persistent job history.
4. Phase 4 — Kubernetes and security: Deploy the service, integrate secrets and identity management, and implement safe execution coordination.
5. Phase 5 — Enterprise rollout: Add production approvals, monitoring, recovery runbooks, access controls, and onboarding for additional application teams.

## 10. One architectural decision before we start coding

There is one question that will materially change the design.

How do you want teams to interact with the centralized wrapper?

A. User interaction

REST API only — orchestration systems call the endpoints.

Web portal — users select releases and environments in a UI.

both Both — APIs for automation and a portal for teams.

B. Migration artifact source

gitlab GitLab repository or package registry.

Migration scripts packaged and published by the CI/CD pipeline.

Not decided yet — recommend an approach.

 Continue with this design



 