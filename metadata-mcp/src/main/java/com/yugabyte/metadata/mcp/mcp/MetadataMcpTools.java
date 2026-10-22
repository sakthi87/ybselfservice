package com.yugabyte.metadata.mcp.mcp;

import com.yugabyte.metadata.mcp.client.MetadataApiClient;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class MetadataMcpTools {
  private static final String READ_ONLY = "This operation is read-only and calls the Metadata API.";

  private final MetadataApiClient metadataApiClient;

  public MetadataMcpTools(MetadataApiClient metadataApiClient) {
    this.metadataApiClient = metadataApiClient;
  }

  @McpTool(
      name = "list_clouds",
      description = "List all active clouds in the YugabyteDB platform. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public List<Map<String, Object>> listClouds() {
    return metadataApiClient.listClouds();
  }

  @McpTool(
      name = "list_environments",
      description = "List environments for a cloud. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public List<Map<String, Object>> listEnvironments(
      @McpToolParam(description = "Cloud code such as AWS or AZURE", required = true) String cloud) {
    return metadataApiClient.listEnvironments(cloud);
  }

  @McpTool(
      name = "list_universes",
      description = "List YugabyteDB universes. Filter by cloud and environment when provided. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public List<Map<String, Object>> listUniverses(
      @McpToolParam(description = "Cloud code", required = false) String cloud,
      @McpToolParam(description = "Environment such as DEV, IT, UAT, or PROD", required = false) String environment) {
    return metadataApiClient.listUniverses(cloud, environment);
  }

  @McpTool(
      name = "get_universe",
      description = "Get YugabyteDB universe information, including regions, nodes, and capacity. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public Map<String, Object> getUniverse(
      @McpToolParam(description = "Universe code, such as AWS-PROD-YB01", required = true) String universe) {
    return metadataApiClient.getUniverse(universe);
  }

  @McpTool(
      name = "list_databases",
      description = "List databases in a YugabyteDB universe. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public List<Map<String, Object>> listDatabases(
      @McpToolParam(description = "Universe code", required = true) String universe) {
    return metadataApiClient.listDatabases(universe);
  }

  @McpTool(
      name = "list_schemas",
      description = "List schemas in a universe database. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public List<Map<String, Object>> listSchemas(
      @McpToolParam(description = "Universe code", required = true) String universe,
      @McpToolParam(description = "Database name", required = true) String database) {
    return metadataApiClient.listSchemas(universe, database);
  }

  @McpTool(
      name = "list_tables",
      description = "List tables in a universe database schema. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public List<Map<String, Object>> listTables(
      @McpToolParam(description = "Universe code", required = true) String universe,
      @McpToolParam(description = "Database name", required = true) String database,
      @McpToolParam(description = "Schema name", required = true) String schema) {
    return metadataApiClient.listTables(universe, database, schema);
  }

  @McpTool(
      name = "get_table",
      description = """
          Retrieve metadata for a YugabyteDB table.
          Returns cloud, environment, universe, database, schema, table, ownership, columns, indexes, and classification.
          This operation is read-only.
          """,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public Map<String, Object> getTable(
      @McpToolParam(description = "YugabyteDB universe code", required = true) String universe,
      @McpToolParam(description = "Database name", required = true) String database,
      @McpToolParam(description = "Schema name", required = true) String schema,
      @McpToolParam(description = "Table name", required = true) String table) {
    return metadataApiClient.getTable(universe, database, schema, table);
  }

  @McpTool(
      name = "get_table_definition",
      description = "Get the table DDL, columns, indexes, constraints, and partitioning. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public Map<String, Object> getTableDefinition(
      @McpToolParam(description = "Universe code", required = true) String universe,
      @McpToolParam(description = "Database name", required = true) String database,
      @McpToolParam(description = "Schema name", required = true) String schema,
      @McpToolParam(description = "Table name", required = true) String table) {
    return metadataApiClient.getTableDefinition(universe, database, schema, table);
  }

  @McpTool(
      name = "list_nodes",
      description = "List universe nodes and capacity. Filter by cloud, environment, universe, region, or role. " + READ_ONLY,
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
  public Map<String, Object> listNodes(
      @McpToolParam(description = "Cloud code", required = false) String cloud,
      @McpToolParam(description = "Environment such as DEV, IT, UAT, or PROD", required = false) String environment,
      @McpToolParam(description = "Universe code", required = false) String universe,
      @McpToolParam(description = "Region code", required = false) String region,
      @McpToolParam(description = "Node role", required = false) String role) {
    return metadataApiClient.listNodes(cloud, environment, universe, region, role);
  }
}
