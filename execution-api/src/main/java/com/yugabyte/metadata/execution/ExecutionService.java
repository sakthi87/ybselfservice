package com.yugabyte.metadata.execution;

import com.yugabyte.metadata.intake.AdminConnections;
import com.yugabyte.metadata.intake.DdlBuilder;
import com.yugabyte.metadata.platform.ServiceClient;
import com.yugabyte.metadata.web.BadRequestException;
import com.yugabyte.metadata.web.CatalogNotUpdatedException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExecutionService {
  private final AdminConnections connections;
  private final DdlBuilder ddl;
  private final ServiceClient clients;

  public ExecutionService(AdminConnections connections, DdlBuilder ddl, ServiceClient clients) {
    this.connections = connections;
    this.ddl = ddl;
    this.clients = clients;
  }

  public Map<String, Object> createDatabase(Map<String, Object> body) {
    String databaseName = ddl.requireName(required(body, "databaseName"), "Database name");
    ddl.requireDatabaseName(databaseName);
    try {
      createDatabase(databaseName);
      return Map.of("databaseName", databaseName, "catalogUpdated", true, "databaseApplied", true);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> createSchema(Map<String, Object> body) {
    String databaseName = ddl.requireName(required(body, "databaseName"), "Database name");
    String schemaName = ddl.requireName(required(body, "schemaName"), "Schema name");
    ddl.rejectPublicSchema(schemaName);
    try (Connection target = connections.open(databaseName)) {
      execute(target, "CREATE SCHEMA " + quote(schemaName));
      return Map.of("databaseName", databaseName, "schemaName", schemaName, "catalogUpdated", true, "databaseApplied", true);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> executeDdl(Map<String, Object> body) {
    String databaseName = required(body, "databaseName");
    String schemaName = required(body, "schemaName");
    List<Map<String, Object>> statements = ddl.previewScript(required(body, "sql"), schemaName);
    try {
      run(databaseName, schemaName, statements.stream().map(item -> String.valueOf(item.get("sql"))).toList());
    } catch (SQLException exception) {
      throw sql(exception);
    }
    try {
      List<Map<String, Object>> tables = registerStatements(statements, databaseName, schemaName, body);
      registerLooseChanges(statements, databaseName, schemaName, body);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("executed", statements.size());
      result.put("catalogUpdated", true);
      result.put("databaseApplied", true);
      result.put("statements", statements);
      result.put("tables", tables);
      return result;
    } catch (CatalogNotUpdatedException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw notRegistered(exception);
    }
  }

  public Map<String, Object> design(Map<String, Object> body) {
    String databaseName = required(body, "databaseName");
    String schemaName = required(body, "schemaName");
    List<String> statements = ddl.statements(required(body, "sql"));
    for (String statement : statements) {
      ddl.assertExecutable(statement);
    }
    try {
      run(databaseName, schemaName, statements);
    } catch (SQLException exception) {
      throw sql(exception);
    }
    try {
      Map<String, Object> registered = clients.catalog("registerTable", map(body.get("registration")));
      Map<String, Object> result = new LinkedHashMap<>(registered);
      result.put("catalogUpdated", true);
      result.put("databaseApplied", true);
      return result;
    } catch (CatalogNotUpdatedException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw notRegistered(exception);
    }
  }

  public Map<String, Object> addColumn(Map<String, Object> body) {
    String schemaName = required(body, "schemaName");
    String statement = required(body, "sql");
    ddl.previewScript(statement, schemaName);
    try {
      run(required(body, "databaseName"), schemaName, List.of(statement));
    } catch (SQLException exception) {
      throw sql(exception);
    }
    try {
      Map<String, Object> registered = clients.catalog("addCatalogColumn", body);
      Map<String, Object> result = new LinkedHashMap<>(registered);
      result.put("databaseApplied", true);
      return result;
    } catch (CatalogNotUpdatedException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw notRegistered(exception);
    }
  }

  public Map<String, Object> addIndex(Map<String, Object> body) {
    String schemaName = required(body, "schemaName");
    String statement = required(body, "sql");
    ddl.previewScript(statement, schemaName);
    try {
      run(required(body, "databaseName"), schemaName, List.of(statement));
    } catch (SQLException exception) {
      throw sql(exception);
    }
    try {
      Map<String, Object> registered = clients.catalog("addCatalogIndex", body);
      Map<String, Object> result = new LinkedHashMap<>(registered);
      result.put("databaseApplied", true);
      return result;
    } catch (CatalogNotUpdatedException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw notRegistered(exception);
    }
  }

  public Map<String, Object> promote(Map<String, Object> body) {
    String environment = required(body, "environment").toUpperCase(Locale.ROOT);
    String cloud = text(body.get("cloud"), "AWS");
    List<Map<String, Object>> tables = maps(body.get("tables"));
    if (tables.isEmpty()) {
      throw new BadRequestException("Select at least one table to promote");
    }
    Map<String, List<Map<String, Object>>> byDatabase = new LinkedHashMap<>();
    for (Map<String, Object> table : tables) {
      byDatabase.computeIfAbsent(required(table, "databaseName"), key -> new ArrayList<>()).add(table);
    }
    List<Map<String, Object>> promotions = new ArrayList<>();
    for (List<Map<String, Object>> group : byDatabase.values()) {
      String sourceName = required(group.get(0), "databaseName");
      String targetName = ddl.targetDatabase(sourceName, environment);
      ddl.requireDatabaseName(targetName);
      try {
        createDatabase(targetName);
      } catch (SQLException exception) {
        throw sql(exception);
      }
      List<String> names = new ArrayList<>();
      List<Map<String, Object>> registered = new ArrayList<>();
      for (Map<String, Object> table : group) {
        String schemaName = required(table, "schemaName");
        String tableName = required(table, "tableName");
        String script = text(table.get("ddl"), "");
        if (script.isBlank()) {
          continue;
        }
        List<String> statements = ddl.statements(script);
        for (String statement : statements) {
          ddl.assertExecutable(statement);
        }
        try (Connection target = connections.open(targetName)) {
          execute(target, "CREATE SCHEMA IF NOT EXISTS " + quote(schemaName));
          executeScript(target, schemaName, statements);
        } catch (SQLException exception) {
          throw sql(exception);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("cloud", cloud);
        request.put("environment", environment);
        request.put("project", table.get("project"));
        try {
          registered.addAll(registerStatements(parsedStatements(script), targetName, schemaName, request));
        } catch (RuntimeException exception) {
          throw notRegistered(exception);
        }
        names.add(schemaName + "." + tableName);
      }
      Map<String, Object> promotion = new LinkedHashMap<>();
      promotion.put("targetDatabase", targetName);
      promotion.put("tables", String.join(", ", names));
      promotion.put("catalog", registered);
      promotions.add(promotion);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("environment", environment);
    result.put("cloud", cloud);
    result.put("catalogUpdated", true);
    result.put("databaseApplied", true);
    result.put("promotions", promotions);
    return result;
  }

  private void registerLooseChanges(
      List<Map<String, Object>> statements, String databaseName, String schemaName, Map<String, Object> body) {
    java.util.Set<String> created = new java.util.HashSet<>();
    for (Map<String, Object> statement : statements) {
      if ("TABLE".equals(statement.get("kind"))) {
        created.add(ddl.parseCreateTable(String.valueOf(statement.get("sql"))).name().toLowerCase(Locale.ROOT));
      }
    }
    Map<String, Object> project = map(body.get("project"));
    String actor = text(project.get("techOwnerEmail"), "");
    for (Map<String, Object> statement : statements) {
      String sqlText = String.valueOf(statement.get("sql"));
      if ("INDEX".equals(statement.get("kind"))) {
        DdlBuilder.ParsedIndex index = ddl.parseCreateIndex(sqlText);
        if (created.contains(index.tableName().toLowerCase(Locale.ROOT))) {
          continue;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cloud", text(body.get("cloud"), "AWS"));
        payload.put("environment", text(body.get("environment"), "DEV"));
        payload.put("databaseName", databaseName);
        payload.put("schemaName", schemaName);
        payload.put("tableName", index.tableName());
        payload.put("indexName", index.indexName());
        payload.put("columns", index.columns());
        payload.put("unique", index.unique());
        payload.put("actor", actor);
        clients.catalog("addCatalogIndex", payload);
      } else if ("COLUMN".equals(statement.get("kind"))) {
        DdlBuilder.AddedColumn column = ddl.parseAddColumn(sqlText);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cloud", text(body.get("cloud"), "AWS"));
        payload.put("environment", text(body.get("environment"), "DEV"));
        payload.put("databaseName", databaseName);
        payload.put("schemaName", schemaName);
        payload.put("tableName", column.tableName());
        payload.put("name", column.name());
        payload.put("type", column.type());
        payload.put("nullable", column.nullable());
        payload.put("actor", actor);
        clients.catalog("addCatalogColumn", payload);
      }
    }
  }

  private List<Map<String, Object>> registerStatements(
      List<Map<String, Object>> statements, String databaseName, String schemaName, Map<String, Object> body) {
    Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
    Map<String, List<DdlBuilder.ParsedIndex>> indexes = new LinkedHashMap<>();
    for (Map<String, Object> statement : statements) {
      String sqlText = String.valueOf(statement.get("sql"));
      String kind = String.valueOf(statement.get("kind"));
      if ("TABLE".equals(kind)) {
        DdlBuilder.ParsedTable parsed = ddl.parseCreateTable(sqlText);
        tables.put(parsed.name().toLowerCase(Locale.ROOT), registration(parsed, databaseName, schemaName, sqlText, body));
        indexes.put(parsed.name().toLowerCase(Locale.ROOT), new ArrayList<>());
      } else if ("INDEX".equals(kind)) {
        DdlBuilder.ParsedIndex parsed = ddl.parseCreateIndex(sqlText);
        indexes.computeIfAbsent(parsed.tableName().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(parsed);
      }
    }
    List<Map<String, Object>> registered = new ArrayList<>();
    for (Map.Entry<String, Map<String, Object>> entry : tables.entrySet()) {
      List<Map<String, Object>> indexPayload = new ArrayList<>();
      for (DdlBuilder.ParsedIndex index : indexes.getOrDefault(entry.getKey(), List.of())) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tableName", index.tableName());
        item.put("indexName", index.indexName());
        item.put("columns", index.columns());
        item.put("unique", index.unique());
        indexPayload.add(item);
      }
      entry.getValue().put("indexes", indexPayload);
      registered.add(clients.catalog("registerTable", entry.getValue()));
    }
    return registered;
  }

  private Map<String, Object> registration(
      DdlBuilder.ParsedTable parsed, String databaseName, String schemaName, String sql, Map<String, Object> body) {
    List<Map<String, Object>> columns = new ArrayList<>();
    for (DdlBuilder.ParsedColumn column : parsed.columns()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", column.name());
      item.put("type", column.type());
      item.put("nullable", column.nullable());
      item.put("primaryKey", column.primaryKey());
      columns.add(item);
    }
    Map<String, Object> registration = new LinkedHashMap<>();
    registration.put("cloud", text(body.get("cloud"), "AWS"));
    registration.put("environment", text(body.get("environment"), "DEV"));
    registration.put("databaseName", databaseName);
    registration.put("schemaName", schemaName);
    registration.put("tableName", parsed.name());
    registration.put("ddl", sql.endsWith(";") ? sql : sql + ";");
    registration.put("partitionKey", parsed.partitionKey());
    registration.put("columns", columns);
    registration.put("project", body.get("project"));
    return registration;
  }

  private List<Map<String, Object>> parsedStatements(String script) {
    List<Map<String, Object>> statements = new ArrayList<>();
    for (String statement : ddl.statements(script)) {
      String lower = statement.trim().toLowerCase(Locale.ROOT);
      String kind = "";
      if (lower.startsWith("create table")) {
        kind = "TABLE";
      } else if (lower.startsWith("create unique index") || lower.startsWith("create index")) {
        kind = "INDEX";
      }
      if (!kind.isBlank()) {
        statements.add(Map.of("kind", kind, "sql", statement));
      }
    }
    return statements;
  }

  private void run(String database, String schema, List<String> statements) throws SQLException {
    try (Connection target = connections.open(database)) {
      executeScript(target, schema, statements);
    }
  }

  private void createDatabase(String databaseName) throws SQLException {
    try (Connection admin = connections.open("yugabyte")) {
      execute(admin, "CREATE DATABASE " + quote(databaseName));
    } catch (SQLException exception) {
      if (exception.getMessage() == null || !exception.getMessage().toLowerCase(Locale.ROOT).contains("already exists")) {
        throw exception;
      }
    }
  }

  private void executeScript(Connection connection, String schema, List<String> statements) throws SQLException {
    execute(connection, "SET search_path TO " + quote(schema));
    for (String statement : statements) {
      String trimmed = statement.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("create schema")) {
        execute(connection, trimmed.endsWith(";") ? trimmed : trimmed + ";");
      } else if (!trimmed.isBlank()) {
        execute(connection, trimmed);
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static CatalogNotUpdatedException notRegistered(RuntimeException exception) {
    String detail = exception.getMessage() == null ? "Catalog registration failed" : exception.getMessage();
    return new CatalogNotUpdatedException(
        "The database change was applied, but the catalog was not updated. Run the same action again. " + detail);
  }

  private static String quote(String name) {
    return "\"" + name + "\"";
  }

  private static BadRequestException sql(SQLException exception) {
    return new BadRequestException(exception.getMessage() == null ? "Database request failed" : exception.getMessage());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> raw) {
      return (Map<String, Object>) raw;
    }
    throw new BadRequestException("Registration payload is required");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    List<Map<String, Object>> items = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          items.add((Map<String, Object>) map);
        }
      }
    }
    return items;
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
