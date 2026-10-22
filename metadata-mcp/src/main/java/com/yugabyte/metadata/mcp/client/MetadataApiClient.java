package com.yugabyte.metadata.mcp.client;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class MetadataApiClient {
  private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {};

  private final RestClient client;

  public MetadataApiClient(
      @Value("${metadata.api.base-url}") String baseUrl,
      @Value("${metadata.api.username}") String username,
      @Value("${metadata.api.password}") String password) {
    this.client = RestClient.builder()
        .baseUrl(baseUrl)
        .defaultHeaders(headers -> headers.setBasicAuth(username, password))
        .build();
  }

  public List<Map<String, Object>> listClouds() {
    return list("query { result: clouds }", Map.of());
  }

  public List<Map<String, Object>> listEnvironments(String cloud) {
    return list("query($cloud: String!) { result: environments(cloud: $cloud) }", Map.of("cloud", cloud));
  }

  public List<Map<String, Object>> listUniverses(String cloud, String environment) {
    return list(
        "query($cloud: String, $environment: String) { result: universes(cloud: $cloud, environment: $environment) }",
        vars("cloud", cloud, "environment", environment));
  }

  public Map<String, Object> getUniverse(String universe) {
    return object("query($id: String!) { result: universe(id: $id) }", Map.of("id", universe));
  }

  public List<Map<String, Object>> listDatabases(String universe) {
    return list("query($universe: String!) { result: databases(universe: $universe) }", Map.of("universe", universe));
  }

  public List<Map<String, Object>> listSchemas(String universe, String database) {
    return list(
        "query($universe: String!, $database: String!) { result: schemas(universe: $universe, database: $database) }",
        Map.of("universe", universe, "database", database));
  }

  public List<Map<String, Object>> listTables(String universe, String database, String schema) {
    return list("""
        query($universe: String!, $database: String!, $schema: String!) {
          result: tables(universe: $universe, database: $database, schema: $schema)
        }
        """, Map.of("universe", universe, "database", database, "schema", schema));
  }

  public Map<String, Object> getTable(String universe, String database, String schema, String table) {
    return object("""
        query($universe: String!, $database: String!, $schema: String!, $name: String!) {
          result: table(universe: $universe, database: $database, schema: $schema, name: $name)
        }
        """, Map.of("universe", universe, "database", database, "schema", schema, "name", table));
  }

  public Map<String, Object> getTableDefinition(String universe, String database, String schema, String table) {
    return object("""
        query($universe: String!, $database: String!, $schema: String!, $name: String!) {
          result: tableDefinition(universe: $universe, database: $database, schema: $schema, name: $name)
        }
        """, Map.of("universe", universe, "database", database, "schema", schema, "name", table));
  }

  public Map<String, Object> listNodes(String cloud, String environment, String universe, String region, String role) {
    Map<String, Object> filter = vars("pageSize", "100", "cloud", cloud, "environment", environment, "universe", universe, "region", region, "role", role);
    return object("query($filter: NodeSearch) { result: searchNodes(filter: $filter) }", Map.of("filter", filter));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(String query, Map<String, Object> variables) {
    return (List<Map<String, Object>>) graphql(query, variables);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> object(String query, Map<String, Object> variables) {
    return (Map<String, Object>) graphql(query, variables);
  }

  private Object graphql(String query, Map<String, Object> variables) {
    Map<String, Object> response = client.post()
        .uri("/graphql")
        .body(Map.of("query", query, "variables", variables))
        .retrieve()
        .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
          String body = new String(httpResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
          throw new IllegalStateException(body.isBlank() ? httpResponse.getStatusCode().toString() : body);
        })
        .body(OBJECT);
    if (response == null) {
      throw new IllegalStateException("Catalog did not return a result");
    }
    Object errors = response.get("errors");
    if (errors instanceof List<?> list && !list.isEmpty()) {
      throw new IllegalStateException(String.valueOf(list.get(0)));
    }
    Object data = response.get("data");
    if (!(data instanceof Map<?, ?> payload)) {
      throw new IllegalStateException("Catalog did not return a result");
    }
    return payload.get("result");
  }

  private static Map<String, Object> vars(String... pairs) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      if (pairs[i + 1] != null && !pairs[i + 1].isBlank()) {
        values.put(pairs[i], pairs[i + 1]);
      }
    }
    return values;
  }
}
