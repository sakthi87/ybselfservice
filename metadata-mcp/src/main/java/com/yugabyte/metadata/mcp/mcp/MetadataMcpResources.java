package com.yugabyte.metadata.mcp.mcp;

import com.yugabyte.metadata.mcp.client.MetadataApiClient;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class MetadataMcpResources {
  private final MetadataApiClient metadataApiClient;
  private final ObjectMapper mapper;

  public MetadataMcpResources(MetadataApiClient metadataApiClient, ObjectMapper mapper) {
    this.metadataApiClient = metadataApiClient;
    this.mapper = mapper;
  }

  @McpResource(
      uri = "metadata://clouds",
      name = "clouds",
      description = "Active clouds in the YugabyteDB metadata catalog",
      mimeType = "application/json")
  public String clouds() {
    return json(metadataApiClient.listClouds());
  }

  @McpResource(
      uri = "metadata://universes/{universe}",
      name = "universe",
      description = "Universe topology, regions, nodes, and capacity",
      mimeType = "application/json")
  public String universe(String universe) {
    return json(metadataApiClient.getUniverse(universe));
  }

  @McpResource(
      uri = "metadata://tables/{universe}/{database}/{schema}/{table}",
      name = "table",
      description = "Simple metadata for one catalog table",
      mimeType = "application/json")
  public String table(String universe, String database, String schema, String table) {
    return json(metadataApiClient.getTable(universe, database, schema, table));
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not write metadata resource", exception);
    }
  }
}
