import { UNIVERSES, initialDemoState } from "./fixture.js";
import { readDemoState, writeDemoState } from "./mode.js";

const ENVIRONMENTS = ["DEV", "IT", "UAT", "PROD"];

export function demoResult(query, variables) {
  const name = operation(query);
  const state = readDemoState(initialDemoState);
  const input = variables || {};
  let changed = false;
  let result;

  switch (name) {
    case "signIn":
      result = { id: "demo-user", userId: "demo.user", subjectId: "user:demo.user" };
      break;
    case "catalogOptions":
      result = options(state);
      break;
    case "databases":
      result = databases(state, input.universe);
      break;
    case "schemasById":
      result = schemas(state, input.databaseId);
      break;
    case "tablesBySchema":
      result = schemaTables(state, input.schemaId);
      break;
    case "searchTables":
      result = searchTables(state, input.filter || {});
      break;
    case "tableById":
      result = tableView(findTable(state, input.tableId));
      break;
    case "tableDefinitionById":
      result = definitionView(findTable(state, input.tableId));
      break;
    case "searchNodes":
      result = page([]);
      break;
    case "node":
      throw new Error("Node was not found");
    case "projects":
      result = state.projects.map(projectSummary);
      break;
    case "project":
      result = projectDetail(findProject(state, input.projectId));
      break;
    case "requests":
      result = state.projects.map((item) => requestView(state, item));
      break;
    case "activity":
      result = activity(state, input);
      break;
    case "openChanges":
      result = state.changes.filter((item) => item.project_id === input.projectId && item.status === "OPEN");
      break;
    case "register":
      result = register(state, input.input || {});
      changed = true;
      break;
    case "createDatabase":
      result = createDatabase(state, input.projectId, input.input || {});
      changed = true;
      break;
    case "createSchema":
      result = createSchema(state, input.databaseId, input.input || {});
      changed = true;
      break;
    case "openChange":
      result = openChange(state, input.projectId, input.input || {});
      changed = true;
      break;
    case "finishChange":
      result = finishChange(state, input.projectId, input.requestId);
      changed = true;
      break;
    case "discardChange":
      discardChange(state, input.projectId, input.requestId);
      result = true;
      changed = true;
      break;
    case "promote":
      result = promote(state, input.projectId, input.input || {});
      changed = true;
      break;
    case "generateModel":
      result = generateModel(input.input || {});
      break;
    case "designTable":
      result = designTable(state, input.schemaId, input.input || {});
      changed = true;
      break;
    case "previewDdl":
      result = { statements: previewSql((input.input || {}).sql) };
      break;
    case "executeDdl":
      result = executeDdl(state, input.schemaId, input.input || {});
      changed = true;
      break;
    case "addColumn":
      result = addColumn(state, input.tableId, input.input || {});
      changed = true;
      break;
    case "addIndex":
      result = addIndex(state, input.tableId, input.input || {});
      changed = true;
      break;
    default:
      throw new Error(`Demo mode cannot run ${name || "this request"}`);
  }

  if (changed) writeDemoState(state);
  return result;
}

function operation(query) {
  const match = String(query).match(/result:\s*(\w+)/);
  return match ? match[1] : "";
}

function options(state) {
  const owners = [];
  for (const table of state.tables) {
    if (!owners.some((owner) => owner.id === table.dataOwnerId)) {
      owners.push({ id: table.dataOwnerId, name: table.ownerName });
    }
  }
  return {
    clouds: [
      { code: "AWS", name: "Amazon Web Services" },
      { code: "AZURE", name: "Microsoft Azure" },
    ],
    environments: ENVIRONMENTS,
    universes: UNIVERSES,
    owners,
    regions: [
      { code: "us-east-1", name: "US East (N. Virginia)" },
      { code: "us-east-2", name: "US East (Ohio)" },
      { code: "centralus", name: "Central US" },
      { code: "eastus", name: "East US" },
    ],
  };
}

function databases(state, universe) {
  const seen = new Map();
  for (const table of state.tables.filter((item) => item.universeCode === universe)) {
    seen.set(table.databaseId, { databaseId: table.databaseId, databaseName: table.databaseName });
  }
  return [...seen.values()];
}

function schemas(state, databaseId) {
  const seen = new Map();
  for (const table of state.tables.filter((item) => item.databaseId === databaseId)) {
    seen.set(table.schemaId, { schemaId: table.schemaId, schemaName: table.schemaName });
  }
  return [...seen.values()];
}

function schemaTables(state, schemaId) {
  return state.tables
    .filter((item) => item.schemaId === schemaId)
    .map((item) => ({ tableId: item.tableId, tableName: item.tableName }));
}

function searchTables(state, filter) {
  const items = state.tables.filter((table) => {
    if (filter.cloud && table.cloud !== filter.cloud) return false;
    if (filter.environment && table.environment !== filter.environment) return false;
    if (filter.universe && table.universeCode !== filter.universe) return false;
    if (filter.database && table.databaseName !== filter.database) return false;
    if (filter.schema && table.schemaName !== filter.schema) return false;
    if (filter.table && table.tableName !== filter.table) return false;
    if (filter.owner && table.dataOwnerId !== filter.owner) return false;
    if (filter.ownerUserId && table.ownerUserId !== filter.ownerUserId) return false;
    if (filter.q) {
      const query = String(filter.q).toLowerCase();
      const haystack = [table.tableName, table.ownerName, table.databaseName, table.schemaName, table.qualifiedName].join(" ").toLowerCase();
      if (!haystack.includes(query)) return false;
    }
    return true;
  }).map(summary);
  return page(items);
}

function summary(table) {
  return {
    tableId: table.tableId,
    qualifiedName: table.qualifiedName,
    tableName: table.tableName,
    tableType: table.tableType,
    status: table.status,
    dataOwnerId: table.dataOwnerId,
    ownerName: table.ownerName,
    schemaId: table.schemaId,
    schemaName: table.schemaName,
    databaseId: table.databaseId,
    databaseName: table.databaseName,
    universeId: table.universeCode,
    universeCode: table.universeCode,
    universeName: table.universeName,
    environment: table.environment,
    cloud: table.cloud,
    updatedAt: table.updatedAt,
  };
}

function tableView(table) {
  return {
    table: table.qualifiedName,
    cloud: table.cloud,
    environment: table.environment,
    universe: table.universeName,
    database: table.databaseName,
    schema: table.schemaName,
    owner: {
      id: table.dataOwnerId,
      name: table.ownerName,
      primaryContact: formatContact(table.contactName, table.contactEmail),
      secondaryContact: table.secondaryEmail ? formatContact(table.secondaryName, table.secondaryEmail) : null,
    },
    tableInfo: { type: table.tableType, status: table.status, partitioned: Boolean(table.partitioned) },
    columns: table.columns,
    indexes: table.indexes,
    metadata: {
      classification: table.classification,
      businessDomain: table.businessDomain,
      lastUpdated: table.updatedAt,
    },
  };
}

function definitionView(table) {
  return {
    target: {
      qualifiedName: table.qualifiedName,
      apiEndpoint: table.apiEndpoint,
      ysql: table.ysql,
    },
    ddl: table.ddl,
    tableInfo: { type: table.tableType, status: table.status, partitioned: Boolean(table.partitioned) },
    partitioning: {
      partitioned: Boolean(table.partitioned),
      strategy: table.strategy,
      definition: table.definition,
    },
    columns: table.columns,
    indexes: table.indexes,
  };
}

function formatContact(name, email) {
  if (!email) return name || null;
  return name ? `${name} <${email}>` : email;
}

function page(items) {
  return { items, page: { pageSize: 50, nextPageToken: null, total: items.length } };
}

function findTable(state, tableId) {
  const table = state.tables.find((item) => item.tableId === tableId);
  if (!table) throw new Error("Table was not found");
  return table;
}

function findProject(state, projectId) {
  const project = state.projects.find((item) => item.project_id === projectId);
  if (!project) throw new Error("Project was not found");
  return project;
}

function projectSummary(project) {
  return {
    project_id: project.project_id,
    edai_req_id: project.edai_req_id,
    project_name: project.project_name,
    business_line: project.business_line,
    domain: project.domain,
    status: project.status,
    description: project.description,
    created_at: project.created_at,
  };
}

function projectDetail(project) {
  return {
    ...project,
    databases: project.databases.map((database) => ({
      ...database,
      schemas: database.schemas,
    })),
  };
}

function requestView(state, project) {
  return {
    ...project,
    databases: project.databases.map((database) => ({
      database_id: database.database_id,
      database_name: database.database_name,
      environment: database.environment,
      schemas: database.schemas.length,
      tables: database.schemas.reduce((count, schema) => count + schema.tables.length, 0),
      deployments: database.deployments,
      tableList: database.schemas.flatMap((schema) => schema.tables.map((table) => ({
        database_id: database.database_id,
        table_id: table.table_id,
        table_name: table.table_name,
        schema_name: schema.schema_name,
      }))),
    })),
    changes: state.changes.filter((item) => item.project_id === project.project_id && item.status !== "DISCARDED"),
  };
}

function activity(state, input) {
  return state.activity.filter((row) => {
    if (input.requestId && !(row.request_id || "").toLowerCase().includes(String(input.requestId).toLowerCase())) return false;
    if (input.action && row.action !== input.action) return false;
    if (input.q) {
      const query = String(input.q).toLowerCase();
      const haystack = `${row.description || ""} ${row.object_name || ""}`.toLowerCase();
      if (!haystack.includes(query)) return false;
    }
    return true;
  });
}

function register(state, input) {
  const name = required(input.projectName, "Project name");
  if (state.projects.some((item) => item.project_name.toLowerCase() === name.toLowerCase())) {
    throw new Error("Project name is already used");
  }
  const projectId = nextId(state, "demo-project");
  const requestId = nextId(state, "REQ");
  const edai = assignOdp(state, input.edaiReqId);
  const now = new Date().toISOString();
  state.projects.unshift({
    project_id: projectId,
    intake_id: requestId,
    edai_req_id: edai,
    project_name: name,
    description: input.projectDescription || "",
    business_line: input.businessLine || "",
    fund_type: input.fundType || "",
    fund_value: input.fundValue || "",
    domain: input.domain || "",
    sub_domain: input.subDomain || "",
    car_id: input.carId || "",
    car_name: input.carName || "",
    tech_owner_email: input.techOwnerEmail || "",
    business_line_owner: input.businessLineOwner || "",
    developer_email: input.developerEmail || "",
    support_assignment_group: input.supportAssignmentGroup || "",
    exp_dev_date: input.expDevDate || "",
    exp_it_date: input.expITDate || "",
    uat_date: input.uatDate || "",
    prod_date: input.prodDate || "",
    status: "SUBMITTED",
    request_type: "NEW",
    created_at: now,
    databases: [],
  });
  record(state, requestId, "INTAKE_SUBMITTED", "project", name, `ODP Req ID ${edai}`);
  return { project_id: projectId, requestId, project_name: name, edai_req_id: edai, databases: [] };
}

function createDatabase(state, projectId, input) {
  const project = findProject(state, projectId);
  const databaseName = requireName(input.databaseName, "Database name");
  if (project.databases.some((item) => item.database_name === databaseName)) {
    throw new Error("Database name is already used on this project");
  }
  const database = {
    database_id: nextId(state, "demo-db"),
    database_name: databaseName,
    environment: "DEV",
    status: "ACTIVE",
    created_at: new Date().toISOString(),
    catalogDatabaseId: nextId(state, "demo-cat-db"),
    deployments: [],
    schemas: [],
  };
  project.databases.push(database);
  record(state, input.requestId, "DATABASE_CREATED", "database", databaseName, `Created database ${databaseName}`);
  return { database_id: database.database_id, database_name: databaseName };
}

function createSchema(state, databaseId, input) {
  const found = findDatabase(state, databaseId);
  const schemaName = requireName(input.schemaName, "Schema name");
  if (schemaName === "public") throw new Error("The public schema is not used");
  if (found.database.schemas.some((item) => item.schema_name === schemaName)) {
    throw new Error("Schema name is already used in this database");
  }
  const schema = {
    schema_id: nextId(state, "demo-schema"),
    schema_name: schemaName,
    created_at: new Date().toISOString(),
    catalogSchemaId: nextId(state, "demo-cat-schema"),
    tables: [],
  };
  found.database.schemas.push(schema);
  record(state, input.requestId, "SCHEMA_CREATED", "schema", schemaName, `Created schema ${schemaName}`);
  return { schema_id: schema.schema_id, schema_name: schemaName };
}

function openChange(state, projectId, input) {
  const project = findProject(state, projectId);
  const description = required(input.description, "Description");
  const open = state.changes.find((item) => item.project_id === projectId && item.status === "OPEN");
  if (open) throw new Error(`Finish or delete ${open.request_id} before starting another change`);
  const requestId = nextId(state, "REQ");
  state.changes.unshift({
    project_id: projectId,
    request_id: requestId,
    description,
    status: "OPEN",
    created_at: new Date().toISOString(),
    work_count: 0,
  });
  record(state, requestId, "CHANGE_STARTED", "project", project.project_name, description);
  return { requestId, description, projectId, status: "OPEN", workCount: 0 };
}

function finishChange(state, projectId, requestId) {
  const change = openChangeRow(state, projectId, requestId);
  change.status = "COMPLETED";
  const project = findProject(state, projectId);
  record(state, requestId, "CHANGE_COMPLETED", "project", project.project_name, "Change request finished");
  return { requestId, description: change.description, projectId, status: "COMPLETED", workCount: change.work_count };
}

function discardChange(state, projectId, requestId) {
  const change = openChangeRow(state, projectId, requestId);
  if (change.work_count > 0) {
    throw new Error("This change already has work. Finish it so the audit stays complete.");
  }
  change.status = "DISCARDED";
}

function promote(state, projectId, input) {
  const project = findProject(state, projectId);
  const environment = required(input.environment, "Environment").toUpperCase();
  if (!["IT", "UAT", "PROD"].includes(environment)) throw new Error("Choose IT, UAT, or PROD");
  const selected = new Set(input.tableIds || []);
  const groups = new Map();
  for (const database of project.databases) {
    for (const schema of database.schemas) {
      for (const table of schema.tables) {
        if (selected.size > 0 && !selected.has(table.table_id)) continue;
        const list = groups.get(database.database_id) || [];
        list.push({ database, schema, table });
        groups.set(database.database_id, list);
      }
    }
  }
  if ([...groups.values()].every((group) => group.length === 0)) {
    throw new Error("Select at least one table");
  }
  const promotions = [];
  for (const group of groups.values()) {
    const sourceName = group[0].database.database_name;
    const targetName = `${sourceName}_${environment.toLowerCase()}`;
    const names = group.map((item) => `${item.schema.schema_name}.${item.table.table_name}`);
    group[0].database.deployments.unshift({
      deployment_id: nextId(state, "demo-deploy"),
      target_environment: environment,
      target_database: targetName,
      status: "DEPLOYED",
      deployed_at: new Date().toISOString(),
    });
    for (const item of group) {
      copyCatalog(state, item, environment, targetName);
    }
    record(state, input.requestId, "PROMOTED", "table", names.join(", "), `Promoted to ${environment} as ${targetName}`);
    promotions.push({ targetDatabase: targetName, tables: names.join(", "), catalog: names });
  }
  return { environment, cloud: "AWS", catalogUpdated: true, databaseApplied: true, promotions };
}

function generateModel(input) {
  const entity = (input.entities || [])[0] || {};
  const fields = entity.fields || [];
  const keys = fields.filter((field) => field.businessKey).map((field) => field.name);
  const partitionKey = keys.length > 0 ? keys : fields.slice(0, 1).map((field) => field.name);
  const indexes = (entity.accessPatterns || []).flatMap((pattern) => (pattern.filters || []).map((filter) => ({ field: filter.field })));
  const columnSql = fields.map((field) => `    ${field.name} ${field.dataType || "text"}`).join(",\n");
  const keySql = partitionKey.length ? `,\n    CONSTRAINT ${entity.entityName || "table"}_pkey PRIMARY KEY ((${partitionKey[0]}) HASH)` : "";
  const createTableCql = `CREATE TABLE ${entity.keyspace || "app"}.${entity.entityName || "unnamed"} (\n${columnSql}${keySql}\n);`;
  const indexCql = indexes.map((item) => `CREATE INDEX ${entity.entityName || "unnamed"}_${item.field}_idx ON ${entity.keyspace || "app"}.${entity.entityName || "unnamed"} (${item.field});`);
  return {
    entities: [{
      partitionKey,
      clusteringKeys: [],
      indexes,
      createTableCql,
      indexCql,
      warnings: fields.length === 0 ? ["Add columns before generating DDL"] : [],
    }],
  };
}

function designTable(state, schemaId, input) {
  const found = findSchema(state, schemaId);
  const tableName = requireName(input.entityName, "Entity name");
  if (found.schema.tables.some((item) => item.table_name === tableName)) {
    throw new Error("Table name is already used in this schema");
  }
  const fields = input.fields || [];
  const keys = fields.filter((field) => field.businessKey);
  const columns = fields.map((field, position) => ({
    column_name: field.name,
    data_type: field.dataType || "text",
    is_nullable: !field.businessKey,
    is_primary_key: Boolean(field.businessKey),
    ordinal_position: position + 1,
  }));
  if (columns.length === 0) throw new Error("Upload a CSV schema first");
  const keyNames = (keys.length ? keys : [fields[0]]).map((field) => field.name);
  const ddl = `CREATE TABLE ${found.schema.schema_name}.${tableName} (\n${columns.map((item) => `    ${item.column_name} ${item.data_type}${item.is_nullable ? "" : " NOT NULL"}`).join(",\n")},\n    CONSTRAINT ${tableName}_pkey PRIMARY KEY ((${keyNames[0]}) HASH)\n);`;
  const table = {
    table_id: nextId(state, "demo-intake"),
    table_name: tableName,
    description: input.entityDescription || input.description || "",
    source: "DESIGN",
    status: "ACTIVE",
    updated_at: new Date().toISOString(),
    ddl,
    columns,
    indexes: [intakeIndex(`${tableName}_pkey`, keyNames.join(", "), true)],
    catalogTableId: nextId(state, "demo-table"),
  };
  found.schema.tables.push(table);
  addCatalogFromIntake(state, found, table, input);
  bumpWork(state, input.requestId);
  record(state, input.requestId, "TABLE_DESIGNED", "table", `${found.schema.schema_name}.${tableName}`, `Designed ${found.schema.schema_name}.${tableName}`);
  return { schema_name: found.schema.schema_name, table_name: tableName };
}

function executeDdl(state, schemaId, input) {
  const statements = previewSql(input.sql);
  const found = findSchema(state, schemaId);
  for (const statement of statements) {
    if (statement.kind === "CREATE TABLE") {
      const tableName = statement.name;
      if (found.schema.tables.some((item) => item.table_name === tableName)) continue;
      const shape = tableShape(statement.sql, tableName);
      const table = {
        table_id: nextId(state, "demo-intake"),
        table_name: tableName,
        description: "Uploaded DDL",
        source: "DDL",
        status: "ACTIVE",
        updated_at: new Date().toISOString(),
        ddl: statement.sql.endsWith(";") ? statement.sql : `${statement.sql};`,
        columns: shape.columns,
        indexes: shape.indexes,
        catalogTableId: nextId(state, "demo-table"),
      };
      found.schema.tables.push(table);
      addCatalogFromIntake(state, found, table, input);
      continue;
    }
    if (statement.kind === "ALTER TABLE") {
      const table = found.schema.tables.find((item) => statement.sql.toLowerCase().includes(item.table_name.toLowerCase()));
      if (!table || table.columns.some((column) => column.column_name === statement.name)) continue;
      const type = (statement.sql.match(/add\s+column\s+(?:if\s+not\s+exists\s+)?\w+\s+([a-z0-9_(),\s]+)/i) || [])[1] || "text";
      table.columns.push(intakeColumn(statement.name, type.trim(), true, false, table.columns.length + 1));
      table.ddl += `\n${statement.sql};`;
      syncCatalog(state, table);
      continue;
    }
    if (statement.kind === "CREATE INDEX") {
      const target = statement.sql.match(/on\s+(?:[\w]+\.)?(\w+)\s*\(([^)]+)\)/i);
      if (!target) continue;
      const table = found.schema.tables.find((item) => item.table_name === target[1]);
      if (!table || table.indexes.some((item) => item.index_name === statement.name)) continue;
      table.indexes.push(intakeIndex(statement.name, target[2].trim(), /unique/i.test(statement.sql)));
      table.ddl += `\n${statement.sql};`;
      syncCatalog(state, table);
    }
  }
  bumpWork(state, input.requestId);
  record(state, input.requestId, "DDL_EXECUTED", "schema", found.schema.schema_name, `Executed ${statements.length} statement${statements.length === 1 ? "" : "s"} in DEV`);
  return { executed: statements.length, statements };
}

function tableShape(sql, tableName) {
  const open = sql.indexOf("(");
  const close = sql.lastIndexOf(")");
  if (open < 0 || close <= open) {
    return { columns: [intakeColumn("id", "uuid", false, true, 1)], indexes: [] };
  }
  const columns = [];
  const primary = [];
  for (const part of splitTopLevel(sql.slice(open + 1, close))) {
    const text = part.trim();
    if (!text) continue;
    const key = text.match(/^(?:constraint\s+\w+\s+)?primary\s+key\s*\(([\s\S]+)\)$/i);
    if (key) {
      primary.push(...keyNames(key[1]));
      continue;
    }
    if (/^(constraint|unique|check|foreign)\b/i.test(text)) continue;
    const column = text.match(/^(\w+)\s+(.+)$/);
    if (!column) continue;
    const inlineKey = /primary\s+key/i.test(column[2]);
    const type = column[2].replace(/\s+(not\s+null|null|default\b[\s\S]*|primary\s+key\b[\s\S]*)$/i, "").trim() || "text";
    columns.push(intakeColumn(column[1], type, !/not\s+null/i.test(column[2]) && !inlineKey, inlineKey, columns.length + 1));
  }
  for (const column of columns) {
    if (primary.includes(column.column_name)) {
      column.is_primary_key = true;
      column.is_nullable = false;
    }
  }
  const indexes = primary.length ? [intakeIndex(`${tableName}_pkey`, primary.join(", "), true)] : [];
  return { columns: columns.length ? columns : [intakeColumn("id", "uuid", false, true, 1)], indexes };
}

function splitTopLevel(body) {
  const parts = [];
  let current = "";
  let depth = 0;
  for (const character of body) {
    if (character === "(") depth += 1;
    if (character === ")") depth = Math.max(0, depth - 1);
    if (character === "," && depth === 0) {
      parts.push(current);
      current = "";
      continue;
    }
    current += character;
  }
  if (current.trim()) parts.push(current);
  return parts;
}

function keyNames(list) {
  return splitTopLevel(list)
    .map((part) => part.replace(/[()]/g, " ").trim().split(/\s+/)[0])
    .filter((name) => name && !/^(hash|asc|desc)$/i.test(name));
}

function addColumn(state, tableId, input) {
  const found = findIntakeTable(state, tableId);
  const name = requireName(input.name, "Column name");
  const type = input.type || "text";
  found.table.columns.push(intakeColumn(name, type, true, false, found.table.columns.length + 1));
  found.table.ddl += `\nALTER TABLE ${found.schema.schema_name}.${found.table.table_name} ADD COLUMN ${name} ${type};`;
  syncCatalog(state, found.table);
  bumpWork(state, input.requestId);
  record(state, input.requestId, "COLUMN_ADDED", "column", name, `Added column ${name}`);
  return { column_name: name };
}

function addIndex(state, tableId, input) {
  const found = findIntakeTable(state, tableId);
  const name = requireName(input.name, "Index name");
  const columns = required(input.columns, "Columns");
  found.table.indexes.push(intakeIndex(name, columns, Boolean(input.unique)));
  found.table.ddl += `\nCREATE INDEX ${name} ON ${found.schema.schema_name}.${found.table.table_name} (${columns});`;
  syncCatalog(state, found.table);
  bumpWork(state, input.requestId);
  record(state, input.requestId, "INDEX_ADDED", "index", name, `Added index ${name}`);
  return { index_name: name };
}

function previewSql(sql) {
  const text = required(sql, "SQL").trim();
  const statements = text.split(";").map((item) => item.trim()).filter(Boolean);
  if (statements.length === 0) throw new Error("SQL is required");
  return statements.map((statement) => {
    if (/\bpublic\b/i.test(statement)) throw new Error("The public schema is not used");
    if (/\b(drop|truncate|delete|insert|update|grant|revoke)\b/i.test(statement)) {
      throw new Error("Only CREATE TABLE, CREATE INDEX, and ALTER TABLE ADD COLUMN are allowed");
    }
    const table = statement.match(/create\s+table\s+(?:if\s+not\s+exists\s+)?(?:(\w+)\.)?(\w+)/i);
    if (table) return { kind: "CREATE TABLE", name: table[2], sql: statement };
    const index = statement.match(/create\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?(?:(\w+)\.)?(\w+)/i);
    if (index) return { kind: "CREATE INDEX", name: index[2], sql: statement };
    const column = statement.match(/alter\s+table\s+[\w.]+\s+add\s+column\s+(?:if\s+not\s+exists\s+)?(\w+)/i);
    if (column) return { kind: "ALTER TABLE", name: column[1], sql: statement };
    throw new Error("Only CREATE TABLE, CREATE INDEX, and ALTER TABLE ADD COLUMN are allowed");
  });
}

function addCatalogFromIntake(state, found, table, input) {
  const universe = UNIVERSES.find((item) => item.cloud === "AWS" && item.environment === "DEV");
  const catalogId = table.catalogTableId;
  state.tables = state.tables.filter((item) => item.tableId !== catalogId);
  state.tables.unshift({
    tableId: catalogId,
    tableName: table.table_name,
    tableType: "BASE_TABLE",
    status: "ACTIVE",
    cloud: "AWS",
    environment: "DEV",
    universeCode: universe.code,
    universeName: universe.name,
    databaseId: found.database.catalogDatabaseId,
    databaseName: found.database.database_name,
    schemaId: found.schema.catalogSchemaId || found.schema.schema_id,
    schemaName: found.schema.schema_name,
    dataOwnerId: "owner-demouser",
    ownerName: found.project.project_name,
    ownerUserId: "demo.user",
    contactName: "Tech Owner",
    contactEmail: found.project.tech_owner_email || "demo.user@example.com",
    secondaryName: null,
    secondaryEmail: null,
    classification: "INTERNAL",
    businessDomain: (found.project.domain || "PLATFORM").toUpperCase(),
    partitioned: table.ddl.includes("HASH"),
    strategy: table.ddl.includes("HASH") ? "HASH" : null,
    definition: table.ddl.includes("HASH") ? "HASH" : null,
    ddl: table.ddl,
    columns: table.columns.map((item) => ({
      name: item.column_name,
      type: item.data_type,
      nullable: item.is_nullable,
      primaryKey: item.is_primary_key,
      description: "",
      ordinal: item.ordinal_position,
    })),
    indexes: table.indexes.map((item) => ({
      name: item.index_name,
      type: item.is_unique ? "UNIQUE" : "INDEX",
      unique: item.is_unique,
      columns: String(item.column_names).split(",").map((name) => name.trim()),
    })),
    updatedAt: new Date().toISOString(),
    apiEndpoint: "localhost:5433",
    ysql: { enabled: true, port: 5433 },
    qualifiedName: `AWS.DEV.${universe.code}.${found.database.database_name}.${found.schema.schema_name}.${table.table_name}`,
  });
  void input;
}

function copyCatalog(state, item, environment, targetName) {
  const source = state.tables.find((table) => table.tableId === item.table.catalogTableId);
  if (!source) return;
  const universe = UNIVERSES.find((entry) => entry.cloud === "AWS" && entry.environment === environment);
  const copyId = `${source.tableId}-${environment.toLowerCase()}`;
  state.tables = state.tables.filter((table) => table.tableId !== copyId);
  state.tables.unshift({
    ...source,
    tableId: copyId,
    environment,
    universeCode: universe.code,
    universeName: universe.name,
    databaseId: `${source.databaseId}-${environment.toLowerCase()}`,
    databaseName: targetName,
    schemaId: `${source.schemaId}-${environment.toLowerCase()}`,
    qualifiedName: `AWS.${environment}.${universe.code}.${targetName}.${source.schemaName}.${source.tableName}`,
    updatedAt: new Date().toISOString(),
  });
}

function syncCatalog(state, intake) {
  const catalog = state.tables.find((table) => table.tableId === intake.catalogTableId);
  if (!catalog) return;
  catalog.ddl = intake.ddl;
  catalog.columns = intake.columns.map((item) => ({
    name: item.column_name,
    type: item.data_type,
    nullable: item.is_nullable,
    primaryKey: item.is_primary_key,
    description: "",
    ordinal: item.ordinal_position,
  }));
  catalog.indexes = intake.indexes.map((item) => ({
    name: item.index_name,
    type: item.is_unique ? "UNIQUE" : "INDEX",
    unique: item.is_unique,
    columns: String(item.column_names).split(",").map((name) => name.trim()),
  }));
  catalog.updatedAt = new Date().toISOString();
}

function findDatabase(state, databaseId) {
  for (const project of state.projects) {
    const database = project.databases.find((item) => item.database_id === databaseId);
    if (database) return { project, database };
  }
  throw new Error("Database was not found");
}

function findSchema(state, schemaId) {
  for (const project of state.projects) {
    for (const database of project.databases) {
      const schema = database.schemas.find((item) => item.schema_id === schemaId);
      if (schema) return { project, database, schema };
    }
  }
  throw new Error("Schema was not found");
}

function findIntakeTable(state, tableId) {
  for (const project of state.projects) {
    for (const database of project.databases) {
      for (const schema of database.schemas) {
        const table = schema.tables.find((item) => item.table_id === tableId);
        if (table) return { project, database, schema, table };
      }
    }
  }
  throw new Error("Table was not found");
}

function openChangeRow(state, projectId, requestId) {
  const change = state.changes.find((item) => item.project_id === projectId && item.request_id === requestId && item.status === "OPEN");
  if (!change) throw new Error("Open change request was not found");
  return change;
}

function bumpWork(state, requestId) {
  const change = state.changes.find((item) => item.request_id === requestId && item.status === "OPEN");
  if (change) change.work_count += 1;
}

function record(state, requestId, action, objectType, objectName, description) {
  state.activity.unshift({
    activity_id: nextId(state, "demo-activity"),
    created_at: new Date().toISOString(),
    request_id: requestId || null,
    action,
    object_type: objectType,
    object_name: objectName,
    description,
    detail: description,
  });
}

function assignOdp(state, supplied) {
  if (/^ODP-[A-Z0-9]{8}$/.test(supplied || "") && !state.projects.some((item) => item.edai_req_id === supplied)) {
    return supplied;
  }
  return nextId(state, "ODP");
}

function nextId(state, prefix) {
  state.seq += 1;
  return `${prefix}-${state.seq}`;
}

function required(value, label) {
  if (value === null || value === undefined || String(value).trim() === "") {
    throw new Error(`${label} is required`);
  }
  return String(value).trim();
}

function requireName(value, label) {
  const name = required(value, label);
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new Error(`${label} must be a SQL name`);
  return name;
}

function intakeColumn(column_name, data_type, is_nullable, is_primary_key, ordinal_position) {
  return { column_name, data_type, is_nullable, is_primary_key, ordinal_position };
}

function intakeIndex(index_name, column_names, is_unique) {
  return { index_name, column_names, is_unique };
}
