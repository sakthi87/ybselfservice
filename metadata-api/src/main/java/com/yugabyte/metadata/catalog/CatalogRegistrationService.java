package com.yugabyte.metadata.catalog;

import com.yugabyte.metadata.intake.AdminConnections;
import com.yugabyte.metadata.intake.CatalogRegistrar;
import com.yugabyte.metadata.intake.DdlBuilder;
import com.yugabyte.metadata.web.BadRequestException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CatalogRegistrationService {
  private final AdminConnections connections;

  public CatalogRegistrationService(AdminConnections connections) {
    this.connections = connections;
  }

  public Map<String, Object> register(Map<String, Object> body) {
    String cloud = text(body.get("cloud"), "AWS");
    String environment = text(body.get("environment"), "DEV").toUpperCase();
    String databaseName = required(body, "databaseName");
    String schemaName = required(body, "schemaName");
    String tableName = required(body, "tableName");
    String ddl = required(body, "ddl");
    List<DdlBuilder.ParsedColumn> columns = columns(body.get("columns"));
    List<String> partitionKey = strings(body.get("partitionKey"));
    CatalogRegistrar.ProjectContext project = project(body.get("project"));
    try (Connection connection = connections.openCatalog()) {
      CatalogRegistrar.Registration registration = CatalogRegistrar.registerUploaded(
          connection, databaseName, schemaName, tableName, columns, partitionKey, ddl, project, cloud, environment, text(body.get("universeCode"), ""));
      String actor = project.techOwnerEmail();
      for (DdlBuilder.ParsedIndex index : indexes(body.get("indexes"))) {
        CatalogRegistrar.addUploadedIndex(connection, registration.catalogTableId(), index, actor);
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("catalogUpdated", true);
      result.put("catalogTableId", registration.catalogTableId().toString());
      result.put("dataOwnerId", registration.dataOwnerId());
      result.put("tableContextId", registration.tableContextId().toString());
      result.put("tableName", tableName);
      result.put("cloud", cloud);
      result.put("environment", environment);
      result.put("databaseName", databaseName);
      return result;
    } catch (SQLException exception) {
      throw new BadRequestException(exception.getMessage() == null ? "Database request failed" : exception.getMessage());
    }
  }

  public Map<String, Object> addColumn(Map<String, Object> body) {
    String cloud = text(body.get("cloud"), "AWS");
    String environment = text(body.get("environment"), "DEV").toUpperCase();
    try (Connection connection = connections.openCatalog()) {
      java.util.UUID tableId = CatalogRegistrar.catalogTableId(
          connection, cloud, environment, text(body.get("universeCode"), ""), required(body, "databaseName"), required(body, "schemaName"), required(body, "tableName"));
      if (tableId == null) {
        throw new BadRequestException("Catalog table was not found for " + cloud + " " + environment);
      }
      CatalogRegistrar.addUploadedColumn(
          connection, tableId, required(body, "name"), required(body, "type"),
          body.get("nullable") == null || Boolean.TRUE.equals(body.get("nullable")), required(body, "actor"));
      return Map.of("catalogUpdated", true, "catalogTableId", tableId.toString());
    } catch (SQLException exception) {
      throw new BadRequestException(exception.getMessage() == null ? "Database request failed" : exception.getMessage());
    }
  }

  public Map<String, Object> addIndex(Map<String, Object> body) {
    String cloud = text(body.get("cloud"), "AWS");
    String environment = text(body.get("environment"), "DEV").toUpperCase();
    DdlBuilder.ParsedIndex index = new DdlBuilder.ParsedIndex(
        required(body, "tableName"), required(body, "indexName"), strings(body.get("columns")), Boolean.TRUE.equals(body.get("unique")));
    try (Connection connection = connections.openCatalog()) {
      java.util.UUID tableId = CatalogRegistrar.catalogTableId(
          connection, cloud, environment, text(body.get("universeCode"), ""), required(body, "databaseName"), required(body, "schemaName"), index.tableName());
      if (tableId == null) {
        throw new BadRequestException("Catalog table was not found for " + cloud + " " + environment);
      }
      CatalogRegistrar.addUploadedIndex(connection, tableId, index, required(body, "actor"));
      return Map.of("catalogUpdated", true, "catalogTableId", tableId.toString());
    } catch (SQLException exception) {
      throw new BadRequestException(exception.getMessage() == null ? "Database request failed" : exception.getMessage());
    }
  }

  private static CatalogRegistrar.ProjectContext project(Object value) {
    Map<String, Object> project = map(value);
    String platformUserId = text(project.get("platformUserId"), "");
    String techOwnerEmail = text(project.get("techOwnerEmail"), "");
    if (platformUserId.isBlank() && techOwnerEmail.isBlank()) {
      throw new BadRequestException("Catalog registration needs a user id");
    }
    return new CatalogRegistrar.ProjectContext(
        required(project, "projectName"),
        text(project.get("businessLine"), ""),
        text(project.get("domain"), ""),
        text(project.get("subDomain"), ""),
        text(project.get("edaiReqId"), ""),
        techOwnerEmail,
        text(project.get("developerEmail"), ""),
        platformUserId,
        text(project.get("carId"), ""));
  }

  private static List<DdlBuilder.ParsedColumn> columns(Object value) {
    List<DdlBuilder.ParsedColumn> columns = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> column = map(item);
        boolean primary = Boolean.TRUE.equals(column.get("primaryKey"));
        boolean nullable = !primary && (column.get("nullable") == null || Boolean.TRUE.equals(column.get("nullable")));
        columns.add(new DdlBuilder.ParsedColumn(required(column, "name"), required(column, "type"), nullable, primary));
      }
    }
    if (columns.isEmpty()) {
      throw new BadRequestException("Catalog registration needs at least one column");
    }
    return columns;
  }

  private static List<DdlBuilder.ParsedIndex> indexes(Object value) {
    List<DdlBuilder.ParsedIndex> indexes = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> index = map(item);
        indexes.add(new DdlBuilder.ParsedIndex(
            text(index.get("tableName"), ""),
            required(index, "indexName"),
            strings(index.get("columns")),
            Boolean.TRUE.equals(index.get("unique"))));
      }
    }
    return indexes;
  }

  private static List<String> strings(Object value) {
    List<String> names = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item != null && !String.valueOf(item).isBlank()) {
          names.add(String.valueOf(item));
        }
      }
    }
    return names;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new BadRequestException("Catalog registration is missing a required object");
  }

  private static String required(Map<String, Object> body, String key) {
    String value = text(body.get(key), "");
    if (value.isBlank()) {
      throw new BadRequestException(key + " is required");
    }
    return value;
  }

  private static String text(Object value, String fallback) {
    if (value == null || String.valueOf(value).isBlank()) {
      return fallback;
    }
    return String.valueOf(value).trim();
  }
}
