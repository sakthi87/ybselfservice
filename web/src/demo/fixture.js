const UPDATED = "2026-03-12T18:40:00Z";

export function initialDemoState() {
  return {
    seq: 200,
    tables: [
      catalogTable({
        tableId: "demo-table-customer",
        tableName: "customer",
        cloud: "AWS",
        environment: "PROD",
        universeCode: "AWS-PROD-YB01",
        universeName: "AWS Production",
        databaseId: "demo-cat-db-hub-prod",
        databaseName: "customer_hub",
        schemaId: "demo-cat-schema-customer-prod",
        schemaName: "customer",
        dataOwnerId: "DOWNER-001",
        ownerName: "Customer Data Platform",
        ownerUserId: "customer.owner",
        carId: "CAR-4401",
        entitlementCode: "ENT-CUSTOMER-HUB",
        entitlementName: "Customer Hub",
        contactName: "Ada Modeler",
        contactEmail: "ada.modeler@example.com",
        classification: "CONFIDENTIAL",
        businessDomain: "CUSTOMER",
        partitioned: false,
        ddl: `CREATE TABLE customer.customer (
    customer_id uuid NOT NULL,
    full_name text NOT NULL,
    email text,
    CONSTRAINT customer_pkey PRIMARY KEY (customer_id)
);`,
        columns: [
          column("customer_id", "uuid", false, true, "Customer identifier", 1),
          column("full_name", "text", false, false, "Customer name", 2),
          column("email", "text", true, false, "Contact email", 3),
        ],
        indexes: [index("customer_pkey", "PRIMARY KEY", true, ["customer_id"])],
      }),
      catalogTable({
        tableId: "demo-table-invoice",
        tableName: "invoice",
        cloud: "AWS",
        environment: "PROD",
        universeCode: "AWS-PROD-YB01",
        universeName: "AWS Production",
        databaseId: "demo-cat-db-hub-prod",
        databaseName: "customer_hub",
        schemaId: "demo-cat-schema-billing-prod",
        schemaName: "billing",
        dataOwnerId: "DOWNER-002",
        ownerName: "Finance",
        ownerUserId: "finance.owner",
        carId: "CAR-4402",
        entitlementCode: "ENT-CUSTOMER-HUB",
        entitlementName: "Customer Hub",
        contactName: "Finance Owner",
        contactEmail: "finance.owner@example.com",
        classification: "INTERNAL",
        businessDomain: "FINANCE",
        partitioned: false,
        ddl: `CREATE TABLE billing.invoice (
    invoice_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    amount numeric(12, 2) NOT NULL,
    CONSTRAINT invoice_pkey PRIMARY KEY (invoice_id)
);`,
        columns: [
          column("invoice_id", "uuid", false, true, "Invoice identifier", 1),
          column("customer_id", "uuid", false, false, "Billed customer", 2),
          column("amount", "numeric(12, 2)", false, false, "Invoice amount", 3),
        ],
        indexes: [index("invoice_pkey", "PRIMARY KEY", true, ["invoice_id"])],
      }),
      catalogTable({
        tableId: "demo-table-account",
        tableName: "account",
        cloud: "AWS",
        environment: "DEV",
        universeCode: "AWS-DEV-YB01",
        universeName: "AWS Development",
        databaseId: "demo-cat-db-hub-dev",
        databaseName: "customer_hub",
        schemaId: "demo-cat-schema-customer-dev",
        schemaName: "customer",
        dataOwnerId: "owner-demouser",
        ownerName: "Customer Hub",
        ownerUserId: "demo.user",
        carId: "CAR-4401",
        entitlementCode: "ENT-CUSTOMER-HUB-DEV",
        entitlementName: "Customer Hub DEV",
        contactName: "Tech Owner",
        contactEmail: "tech.owner@example.com",
        secondaryName: "Dev Contact",
        secondaryEmail: "dev.contact@example.com",
        classification: "INTERNAL",
        businessDomain: "CUSTOMER",
        partitioned: true,
        strategy: "HASH",
        definition: "HASH (account_id)",
        ddl: `CREATE TABLE customer.account (
    account_id uuid NOT NULL,
    opened_at timestamptz NOT NULL,
    status text NOT NULL,
    CONSTRAINT account_pkey PRIMARY KEY ((account_id) HASH, opened_at)
);`,
        columns: [
          column("account_id", "uuid", false, true, "Account identifier", 1),
          column("opened_at", "timestamptz", false, true, "When the account opened", 2),
          column("status", "text", false, false, "Account status", 3),
        ],
        indexes: [index("account_pkey", "PRIMARY KEY", true, ["account_id", "opened_at"])],
      }),
      catalogTable({
        tableId: "demo-table-session",
        tableName: "reader_session",
        cloud: "AWS",
        environment: "DEV",
        universeCode: "AWS-DEV-YB01",
        universeName: "AWS Development",
        databaseId: "demo-cat-db-gql",
        databaseName: "gql_flow",
        schemaId: "demo-cat-schema-gql",
        schemaName: "gql",
        dataOwnerId: "owner-demouser",
        ownerName: "Demo Reader",
        ownerUserId: "demo.user",
        entitlementCode: "ENT-GQL-FLOW",
        entitlementName: "GraphQL flow",
        contactName: "Demo User",
        contactEmail: "demo.user@example.com",
        classification: "INTERNAL",
        businessDomain: "PLATFORM",
        partitioned: true,
        strategy: "HASH",
        definition: "HASH (session_id)",
        ddl: `CREATE TABLE gql.reader_session (
    session_id uuid NOT NULL,
    opened_at timestamptz NOT NULL,
    status text NOT NULL,
    CONSTRAINT reader_session_pkey PRIMARY KEY ((session_id) HASH, opened_at)
);`,
        columns: [
          column("session_id", "uuid", false, true, "Session identifier", 1),
          column("opened_at", "timestamptz", false, true, "Session start", 2),
          column("status", "text", false, false, "Session status", 3),
        ],
        indexes: [index("reader_session_pkey", "PRIMARY KEY", true, ["session_id", "opened_at"])],
      }),
      catalogTable({
        tableId: "demo-table-role",
        tableName: "role",
        cloud: "AZURE",
        environment: "DEV",
        universeCode: "AZURE-DEV-YB01",
        universeName: "Azure Development",
        databaseId: "demo-cat-db-authz",
        databaseName: "authz_team",
        schemaId: "demo-cat-schema-authz",
        schemaName: "authz",
        dataOwnerId: "owner-authz",
        ownerName: "AuthZ Team",
        ownerUserId: "authz.owner",
        entitlementCode: "ENT-AUTHZ",
        entitlementName: "AuthZ database",
        contactName: "AuthZ Owner",
        contactEmail: "authz.owner@example.com",
        classification: "INTERNAL",
        businessDomain: "SECURITY",
        partitioned: false,
        ddl: `CREATE TABLE authz.role (
    role_id uuid NOT NULL,
    role_name text NOT NULL,
    CONSTRAINT role_pkey PRIMARY KEY (role_id)
);`,
        columns: [
          column("role_id", "uuid", false, true, "Role identifier", 1),
          column("role_name", "text", false, false, "Role name", 2),
        ],
        indexes: [index("role_pkey", "PRIMARY KEY", true, ["role_id"])],
      }),
    ],
    projects: [
      project({
        project_id: "demo-project-hub",
        intake_id: "REQ-DEMO1001",
        edai_req_id: "ODP-DEMO1001",
        project_name: "Customer Hub",
        description: "Sample customer account model for the demo walkthrough",
        business_line: "Platform",
        fund_type: "capital",
        fund_value: "250000",
        domain: "Customer",
        sub_domain: "Accounts",
        car_id: "CAR-4401",
        car_name: "Customer Hub CAR",
        tech_owner_email: "tech.owner@example.com",
        business_line_owner: "Line Owner",
        developer_email: "dev.contact@example.com",
        support_assignment_group: "YB-SUPPORT",
        exp_dev_date: "2026-11-01",
        exp_it_date: "2026-12-01",
        uat_date: "2027-01-15",
        prod_date: "2027-02-01",
        status: "SUBMITTED",
        created_at: "2026-03-02T15:04:00Z",
        databases: [
          database({
            database_id: "demo-db-hub",
            database_name: "customer_hub",
            catalogDatabaseId: "demo-cat-db-hub-dev",
            catalogSchemaId: "demo-cat-schema-customer-dev",
            schemas: [
              schema({
                schema_id: "demo-schema-customer",
                catalogSchemaId: "demo-cat-schema-customer-dev",
                schema_name: "customer",
                tables: [
                  intakeTable({
                    table_id: "demo-intake-account",
                    table_name: "account",
                    description: "Account master",
                    catalogTableId: "demo-table-account",
                    ddl: `CREATE TABLE customer.account (
    account_id uuid NOT NULL,
    opened_at timestamptz NOT NULL,
    status text NOT NULL,
    CONSTRAINT account_pkey PRIMARY KEY ((account_id) HASH, opened_at)
);`,
                    columns: [
                      intakeColumn("account_id", "uuid", false, true, 1),
                      intakeColumn("opened_at", "timestamptz", false, true, 2),
                      intakeColumn("status", "text", false, false, 3),
                    ],
                    indexes: [intakeIndex("account_pkey", "account_id, opened_at", true)],
                  }),
                ],
              }),
            ],
          }),
        ],
      }),
      project({
        project_id: "demo-project-pay",
        intake_id: "REQ-DEMO2002",
        edai_req_id: "ODP-DEMO2002",
        project_name: "Payments",
        description: "A second request with no tables yet",
        business_line: "Payments",
        fund_type: "operational",
        fund_value: "40000",
        domain: "Finance",
        sub_domain: "Billing",
        car_id: "CAR-4402",
        car_name: "Payments CAR",
        tech_owner_email: "payments.owner@example.com",
        business_line_owner: "Payments Owner",
        developer_email: "payments.dev@example.com",
        support_assignment_group: "PAY-SUPPORT",
        exp_dev_date: "2026-11-15",
        exp_it_date: "2026-12-15",
        uat_date: "2027-01-20",
        prod_date: "2027-02-15",
        status: "SUBMITTED",
        created_at: "2026-03-04T11:20:00Z",
        databases: [],
      }),
    ],
    changes: [
      {
        project_id: "demo-project-hub",
        request_id: "REQ-DEMO1001",
        description: "Initial account table",
        status: "COMPLETED",
        created_at: "2026-03-02T15:10:00Z",
        work_count: 3,
      },
    ],
    entitlements: [
      entitlement({
        entitlementId: "demo-ent-hub-prod",
        code: "ENT-CUSTOMER-HUB",
        name: "Customer Hub",
        description: "Access to customer_hub in AWS production. Every table in that database carries this entitlement.",
        databaseId: "demo-cat-db-hub-prod",
        databaseName: "customer_hub",
        ownerName: "Customer Data Platform",
        cloud: "AWS",
        environment: "PROD",
        universeCode: "AWS-PROD-YB01",
        members: [
          member("demo.user", "Demo User", "demo.user@example.com", "OWNER"),
          member("ada.modeler", "Ada Modeler", "ada.modeler@example.com", "MEMBER"),
        ],
      }),
      entitlement({
        entitlementId: "demo-ent-hub-dev",
        code: "ENT-CUSTOMER-HUB-DEV",
        name: "Customer Hub DEV",
        description: "Access to customer_hub in AWS development.",
        databaseId: "demo-cat-db-hub-dev",
        databaseName: "customer_hub",
        ownerName: "Customer Hub",
        cloud: "AWS",
        environment: "DEV",
        universeCode: "AWS-DEV-YB01",
        members: [
          member("demo.user", "Demo User", "demo.user@example.com", "MEMBER"),
          member("tech.owner", "Tech Owner", "tech.owner@example.com", "OWNER"),
        ],
      }),
      entitlement({
        entitlementId: "demo-ent-gql",
        code: "ENT-GQL-FLOW",
        name: "GraphQL flow",
        description: "Access to the gql_flow database.",
        databaseId: "demo-cat-db-gql",
        databaseName: "gql_flow",
        ownerName: "Demo Reader",
        cloud: "AWS",
        environment: "DEV",
        universeCode: "AWS-DEV-YB01",
        members: [
          member("demo.user", "Demo User", "demo.user@example.com", "OWNER"),
        ],
      }),
      entitlement({
        entitlementId: "demo-ent-authz",
        code: "ENT-AUTHZ",
        name: "AuthZ database",
        description: "Access to authz_team on Azure development.",
        databaseId: "demo-cat-db-authz",
        databaseName: "authz_team",
        ownerName: "AuthZ Team",
        cloud: "AZURE",
        environment: "DEV",
        universeCode: "AZURE-DEV-YB01",
        members: [
          member("authz.owner", "AuthZ Owner", "authz.owner@example.com", "OWNER"),
        ],
      }),
    ],
    activity: [
      activity("1", "2026-03-02T15:00:00Z", null, "SIGN_IN", "user", "demo.user", "Signed in as demo.user"),
      activity("2", "2026-03-02T15:04:00Z", "REQ-DEMO1001", "INTAKE_SUBMITTED", "project", "Customer Hub", "ODP Req ID ODP-DEMO1001"),
      activity("3", "2026-03-02T15:06:00Z", "REQ-DEMO1001", "DATABASE_CREATED", "database", "customer_hub", "Created database customer_hub"),
      activity("4", "2026-03-02T15:07:00Z", "REQ-DEMO1001", "SCHEMA_CREATED", "schema", "customer", "Created schema customer"),
      activity("5", "2026-03-02T15:12:00Z", "REQ-DEMO1001", "TABLE_DESIGNED", "table", "customer.account", "Designed customer.account"),
      activity("6", "2026-03-02T15:13:00Z", "REQ-DEMO1001", "DDL_EXECUTED", "table", "customer.account", "Executed DDL for customer.account in DEV"),
      activity("7", "2026-03-04T11:20:00Z", "REQ-DEMO2002", "INTAKE_SUBMITTED", "project", "Payments", "ODP Req ID ODP-DEMO2002"),
    ],
  };
}

function catalogTable(table) {
  return {
    updatedAt: UPDATED,
    tableType: "BASE_TABLE",
    status: "ACTIVE",
    apiEndpoint: "localhost:5433",
    ysql: { enabled: true, port: 5433 },
    strategy: null,
    definition: null,
    secondaryName: null,
    secondaryEmail: null,
    ...table,
    qualifiedName: `${table.cloud}.${table.environment}.${table.universeCode}.${table.databaseName}.${table.schemaName}.${table.tableName}`,
  };
}

function column(name, type, nullable, primaryKey, description, ordinal) {
  return { name, type, nullable, primaryKey, description, ordinal };
}

function index(name, type, unique, columns) {
  return { name, type, unique, columns };
}

function project(value) {
  return { request_type: "NEW", ...value };
}

function database(value) {
  return { environment: "DEV", status: "ACTIVE", created_at: "2026-03-02T15:06:00Z", deployments: [], ...value };
}

function schema(value) {
  return { created_at: "2026-03-02T15:07:00Z", ...value };
}

function intakeTable(value) {
  return { source: "DESIGN", status: "ACTIVE", updated_at: UPDATED, ...value };
}

function intakeColumn(column_name, data_type, is_nullable, is_primary_key, ordinal_position) {
  return { column_name, data_type, is_nullable, is_primary_key, ordinal_position };
}

function intakeIndex(index_name, column_names, is_unique) {
  return { index_name, column_names, is_unique };
}

function entitlement(value) {
  return value;
}

function member(userId, fullName, email, association) {
  return { userId, fullName, email, association };
}

function activity(activity_id, created_at, request_id, action, object_type, object_name, description) {
  return { activity_id, created_at, request_id, action, object_type, object_name, description, detail: description };
}

export const UNIVERSES = [
  { code: "AWS-DEV-YB01", name: "AWS Development", cloud: "AWS", environment: "DEV" },
  { code: "AWS-IT-YB01", name: "AWS IT", cloud: "AWS", environment: "IT" },
  { code: "AWS-UAT-YB01", name: "AWS UAT", cloud: "AWS", environment: "UAT" },
  { code: "AWS-PROD-YB01", name: "AWS Production", cloud: "AWS", environment: "PROD" },
  { code: "AZURE-DEV-YB01", name: "Azure Development", cloud: "AZURE", environment: "DEV" },
  { code: "AZURE-IT-YB01", name: "Azure IT", cloud: "AZURE", environment: "IT" },
  { code: "AZURE-UAT-YB01", name: "Azure UAT", cloud: "AZURE", environment: "UAT" },
  { code: "AZURE-PROD-YB01", name: "Azure Production", cloud: "AZURE", environment: "PROD" },
];
