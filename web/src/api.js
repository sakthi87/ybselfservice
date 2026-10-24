import { gql } from "./graphql.js";

const CATALOG = "/graphql";

async function result(query, variables) {
  const data = await gql(CATALOG, query, variables);
  return data.result;
}

export function getEntitlements(userId) {
  return result(
    "query($userId: String) { result: entitlements(userId: $userId) }",
    { userId: userId || null }
  );
}

export function getEntitlement(entitlementId) {
  return result(
    "query($entitlementId: ID!) { result: entitlement(entitlementId: $entitlementId) }",
    { entitlementId }
  );
}

export function getOptions() {
  return result("query { result: catalogOptions }");
}

export function getDatabases(universeCode) {
  return result(
    "query($universe: String!) { result: databases(universe: $universe) }",
    { universe: universeCode }
  );
}

export function getSchemas(databaseId) {
  return result(
    "query($databaseId: ID!) { result: schemasById(databaseId: $databaseId) }",
    { databaseId }
  );
}

export function getSchemaTables(schemaId) {
  return result(
    "query($schemaId: ID!) { result: tablesBySchema(schemaId: $schemaId) }",
    { schemaId }
  );
}

export function searchTables(filters) {
  return result(
    "query($filter: TableSearch) { result: searchTables(filter: $filter) }",
    { filter: { pageSize: "50", ...filters } }
  );
}

export function getTable(tableId) {
  return result(
    "query($tableId: ID!) { result: tableById(tableId: $tableId) }",
    { tableId }
  );
}

export function getTableDefinition(tableId) {
  return result(
    "query($tableId: ID!) { result: tableDefinitionById(tableId: $tableId) }",
    { tableId }
  );
}

export function searchNodes(filters) {
  return result(
    "query($filter: NodeSearch) { result: searchNodes(filter: $filter) }",
    { filter: { pageSize: "100", ...filters } }
  );
}

export function getNode(nodeId) {
  return result(
    "query($nodeId: ID!) { result: node(nodeId: $nodeId) }",
    { nodeId }
  );
}
