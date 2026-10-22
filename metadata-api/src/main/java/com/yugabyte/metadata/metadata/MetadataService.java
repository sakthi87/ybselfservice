package com.yugabyte.metadata.metadata;

import com.yugabyte.metadata.catalog.CatalogService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MetadataService {
  private final CatalogService catalog;

  public MetadataService(CatalogService catalog) {
    this.catalog = catalog;
  }

  public Map<String, Object> health() {
    return catalog.health();
  }

  public List<Map<String, Object>> listClouds() {
    return catalog.clouds();
  }

  public List<Map<String, Object>> listEnvironments(String cloud) {
    return catalog.environments(cloud);
  }

  public List<Map<String, Object>> listUniverses(String cloud, String environment) {
    return catalog.listUniverses(cloud, environment);
  }

  public Map<String, Object> getUniverse(String universe) {
    return catalog.universe(universe);
  }

  public List<Map<String, Object>> listDatabases(String universe) {
    return catalog.databases(universe);
  }

  public List<Map<String, Object>> listSchemas(String universe, String database) {
    return catalog.schemas(universe, database);
  }

  public List<Map<String, Object>> listSchemas(String databaseId) {
    return catalog.schemas(databaseId);
  }

  public List<Map<String, Object>> listTables(String universe, String database, String schema) {
    return catalog.tables(universe, database, schema);
  }

  public List<Map<String, Object>> listTables(String schemaId) {
    return catalog.schemaTables(schemaId);
  }

  public Map<String, Object> getTable(String universe, String database, String schema, String table) {
    return catalog.table(universe, database, schema, table);
  }

  public Map<String, Object> getTable(String tableId) {
    return catalog.table(tableId);
  }

  public Map<String, Object> getTableDefinition(String universe, String database, String schema, String table) {
    return catalog.tableDefinition(universe, database, schema, table);
  }

  public Map<String, Object> getTableDefinition(String tableId) {
    return catalog.tableDefinition(tableId);
  }

  public List<Map<String, Object>> universesForCloud(String cloud, String environment) {
    return catalog.universesForCloud(cloud, environment);
  }

  public List<Map<String, Object>> universesForEnvironment(String environment, String cloud) {
    return catalog.universesForEnvironment(environment, cloud);
  }

  public List<Map<String, Object>> regions(String universe) {
    return catalog.regions(universe);
  }

  public List<Map<String, Object>> universeNodes(String universe) {
    return catalog.universeNodes(universe);
  }

  public Map<String, Object> searchTables(String pageSize, String pageToken, String page, Map<String, String> filters) {
    return catalog.searchTables(pageSize, pageToken, page, filters);
  }

  public Map<String, Object> searchNodes(String pageSize, String pageToken, String page, Map<String, String> filters) {
    return catalog.searchNodes(pageSize, pageToken, page, filters);
  }

  public Map<String, Object> node(String nodeId) {
    return catalog.node(nodeId);
  }

  public List<Map<String, Object>> owners() {
    return catalog.owners();
  }

  public Map<String, Object> options() {
    return catalog.options();
  }
}
