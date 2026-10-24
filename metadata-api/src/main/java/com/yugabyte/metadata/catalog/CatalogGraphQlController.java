package com.yugabyte.metadata.catalog;

import com.yugabyte.metadata.metadata.MetadataService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class CatalogGraphQlController {
  private final MetadataService catalog;
  private final CatalogRegistrationService registration;

  public CatalogGraphQlController(MetadataService catalog, CatalogRegistrationService registration) {
    this.catalog = catalog;
    this.registration = registration;
  }

  @QueryMapping
  Map<String, Object> health() {
    return Map.of("status", "UP", "api", "catalog");
  }

  @QueryMapping
  Object clouds() {
    return catalog.listClouds();
  }

  @QueryMapping
  Object environments(@Argument String cloud) {
    return catalog.listEnvironments(cloud);
  }

  @QueryMapping
  Object universes(@Argument String cloud, @Argument String environment) {
    return catalog.listUniverses(cloud, environment);
  }

  @QueryMapping
  Object universesForCloud(@Argument String cloud, @Argument String environment) {
    return catalog.universesForCloud(cloud, environment);
  }

  @QueryMapping
  Object universesForEnvironment(@Argument String environment, @Argument String cloud) {
    return catalog.universesForEnvironment(environment, cloud);
  }

  @QueryMapping
  Object universe(@Argument String id) {
    return catalog.getUniverse(id);
  }

  @QueryMapping
  Object regions(@Argument String universe) {
    return catalog.regions(universe);
  }

  @QueryMapping
  Object universeNodes(@Argument String universe) {
    return catalog.universeNodes(universe);
  }

  @QueryMapping
  Object databases(@Argument String universe) {
    return catalog.listDatabases(universe);
  }

  @QueryMapping
  Object schemas(@Argument String universe, @Argument String database) {
    return catalog.listSchemas(universe, database);
  }

  @QueryMapping
  Object schemasById(@Argument String databaseId) {
    return catalog.listSchemas(databaseId);
  }

  @QueryMapping
  Object tables(@Argument String universe, @Argument String database, @Argument String schema) {
    return catalog.listTables(universe, database, schema);
  }

  @QueryMapping
  Object tablesBySchema(@Argument String schemaId) {
    return catalog.listTables(schemaId);
  }

  @QueryMapping
  Object table(@Argument String universe, @Argument String database, @Argument String schema, @Argument String name) {
    return catalog.getTable(universe, database, schema, name);
  }

  @QueryMapping
  Object tableById(@Argument String tableId) {
    return catalog.getTable(tableId);
  }

  @QueryMapping
  Object tableDefinition(@Argument String universe, @Argument String database, @Argument String schema, @Argument String name) {
    return catalog.getTableDefinition(universe, database, schema, name);
  }

  @QueryMapping
  Object tableDefinitionById(@Argument String tableId) {
    return catalog.getTableDefinition(tableId);
  }

  @QueryMapping
  Object searchTables(@Argument Map<String, Object> filter) {
    Map<String, String> values = strings(filter);
    return catalog.searchTables(values.get("pageSize"), values.get("pageToken"), values.get("page"), values);
  }

  @QueryMapping
  Object searchNodes(@Argument Map<String, Object> filter) {
    Map<String, String> values = strings(filter);
    return catalog.searchNodes(values.get("pageSize"), values.get("pageToken"), values.get("page"), values);
  }

  @QueryMapping
  Object node(@Argument String nodeId) {
    return catalog.node(nodeId);
  }

  @QueryMapping
  Object owners() {
    return catalog.owners();
  }

  @QueryMapping
  Object catalogOptions() {
    return catalog.options();
  }

  @QueryMapping
  Object entitlements(@Argument String userId) {
    return catalog.entitlements(userId);
  }

  @QueryMapping
  Object entitlement(@Argument String entitlementId) {
    return catalog.entitlement(entitlementId);
  }

  @MutationMapping
  Object registerTable(@Argument Map<String, Object> input) {
    return registration.register(input);
  }

  @MutationMapping
  Object addCatalogColumn(@Argument Map<String, Object> input) {
    return registration.addColumn(input);
  }

  @MutationMapping
  Object addCatalogIndex(@Argument Map<String, Object> input) {
    return registration.addIndex(input);
  }

  private static Map<String, String> strings(Map<String, Object> filter) {
    Map<String, String> values = new LinkedHashMap<>();
    if (filter == null) {
      return values;
    }
    filter.forEach((key, value) -> values.put(key, value == null ? null : String.valueOf(value)));
    return values;
  }
}
