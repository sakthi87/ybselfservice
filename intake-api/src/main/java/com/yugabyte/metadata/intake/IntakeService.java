package com.yugabyte.metadata.intake;

import com.yugabyte.metadata.intake.modeler.AccessPattern;
import com.yugabyte.metadata.intake.modeler.Cardinality;
import com.yugabyte.metadata.intake.modeler.ConstraintSettings;
import com.yugabyte.metadata.intake.modeler.EntityModelRequest;
import com.yugabyte.metadata.intake.modeler.EntityModelResponse;
import com.yugabyte.metadata.intake.modeler.FieldMetadata;
import com.yugabyte.metadata.intake.modeler.FilterType;
import com.yugabyte.metadata.intake.modeler.ModelingRequest;
import com.yugabyte.metadata.intake.modeler.ModelingResponse;
import com.yugabyte.metadata.intake.modeler.PartitionSizeExpectation;
import com.yugabyte.metadata.intake.modeler.PatternField;
import com.yugabyte.metadata.intake.modeler.QueryVolume;
import com.yugabyte.metadata.intake.modeler.SortField;
import com.yugabyte.metadata.intake.modeler.YugabyteModelerService;
import com.yugabyte.metadata.platform.ServiceClient;
import com.yugabyte.metadata.web.BadRequestException;
import com.yugabyte.metadata.web.NotFoundException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IntakeService {
  private final AdminConnections connections;
  private final DdlBuilder ddl;
  private final YugabyteModelerService modeler;
  private final ServiceClient clients;

  public IntakeService(AdminConnections connections, DdlBuilder ddl, YugabyteModelerService modeler, ServiceClient clients) {
    this.connections = connections;
    this.ddl = ddl;
    this.modeler = modeler;
    this.clients = clients;
  }

  public ModelingResponse generate(ModelingRequest request) {
    try {
      return modeler.generateModels(request);
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException(exception.getMessage());
    }
  }

  public Map<String, Object> session(Map<String, Object> body) {
    return clients.signIn(body);
  }

  public Map<String, Object> register(Map<String, Object> body) {
    String loginId = required(body, "userId").toLowerCase(Locale.ROOT);
    String projectName = required(body, "projectName");
    Map<String, Object> user = clients.ensureUser(loginId);
    UUID userId = UUID.fromString(String.valueOf(user.get("id")));
    try (Connection connection = connections.openCatalog()) {
      String requestId = newRequestId();
      String odpReqId = assignOdpReqId(connection, text(body.get("edaiReqId")));
      UUID projectId = insert(connection, """
          INSERT INTO catalog.intake_project (
              user_id, project_name, description, business_line, tech_owner_email,
              edai_req_id, fund_type, fund_value, sub_domain, domain, developer_email,
              car_id, car_name, business_line_owner, support_assignment_group,
              exp_dev_date, exp_it_date, uat_date, prod_date, request_type, intake_id)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS date), CAST(? AS date), CAST(? AS date), CAST(? AS date), ?, ?)
          RETURNING project_id
          """,
          userId,
          projectName,
          firstText(body, "projectDescription", "description"),
          text(body.get("businessLine")),
          text(body.get("techOwnerEmail")),
          odpReqId,
          text(body.get("fundType")),
          decimalOrNull(body.get("fundValue")),
          text(body.get("subDomain")),
          text(body.get("domain")),
          text(body.get("developerEmail")),
          text(body.get("carId")),
          text(body.get("carName")),
          text(body.get("businessLineOwner")),
          text(body.get("supportAssignmentGroup")),
          dateOrNull(body.get("expDevDate")),
          dateOrNull(body.get("expITDate")),
          dateOrNull(body.get("uatDate")),
          dateOrNull(body.get("prodDate")),
          requestType(body),
          requestId);
      record(connection, loginId, "INTAKE_SUBMITTED", "project", projectName,
          "ODP Req ID " + odpReqId + " · " + text(body.get("domain")),
          requestId, firstText(body, "projectDescription", "description"));
      Map<String, Object> result = project(connection, projectId);
      result.put("userId", loginId);
      result.put("id", userId.toString());
      result.put("requestId", requestId);
      return result;
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public List<Map<String, Object>> projects(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new BadRequestException("User id is required");
    }
    try (Connection connection = connections.openCatalog()) {
      return query(connection, """
          SELECT p.project_id, p.project_name, p.description, p.business_line, p.domain,
                 p.edai_req_id, p.request_type, p.status, p.created_at
            FROM catalog.intake_project p
            JOIN catalog.intake_user u ON u.user_id = p.user_id
           WHERE lower(u.login_id) = lower(?)
           ORDER BY p.created_at DESC
          """, userId.trim());
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public List<Map<String, Object>> requests(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new BadRequestException("User id is required");
    }
    try (Connection connection = connections.openCatalog()) {
      List<Map<String, Object>> projects = query(connection, """
          SELECT p.project_id, p.intake_id, p.project_name, p.description, p.business_line, p.domain, p.sub_domain,
                 p.edai_req_id, p.fund_type, p.fund_value, p.developer_email, p.tech_owner_email,
                 p.car_id, p.car_name, p.business_line_owner, p.support_assignment_group,
                 p.exp_dev_date, p.exp_it_date, p.uat_date, p.prod_date, p.request_type, p.status, p.created_at
            FROM catalog.intake_project p
            JOIN catalog.intake_user u ON u.user_id = p.user_id
           WHERE lower(u.login_id) = lower(?)
           ORDER BY p.created_at DESC
          """, userId.trim());
      List<Map<String, Object>> databases = query(connection, """
          SELECT d.project_id, d.database_id, d.database_name, d.environment,
                 (SELECT count(*) FROM catalog.intake_schema s WHERE s.database_id = d.database_id) AS schemas,
                 (SELECT count(*) FROM catalog.intake_table t
                    JOIN catalog.intake_schema s ON s.schema_id = t.schema_id
                   WHERE s.database_id = d.database_id) AS tables
            FROM catalog.intake_database d
            JOIN catalog.intake_project p ON p.project_id = d.project_id
            JOIN catalog.intake_user u ON u.user_id = p.user_id
           WHERE lower(u.login_id) = lower(?)
           ORDER BY d.database_name
          """, userId.trim());
      List<Map<String, Object>> tables = query(connection, """
          SELECT d.database_id, t.table_id, t.table_name, s.schema_name
            FROM catalog.intake_table t
            JOIN catalog.intake_schema s ON s.schema_id = t.schema_id
            JOIN catalog.intake_database d ON d.database_id = s.database_id
            JOIN catalog.intake_project p ON p.project_id = d.project_id
            JOIN catalog.intake_user u ON u.user_id = p.user_id
           WHERE lower(u.login_id) = lower(?)
           ORDER BY s.schema_name, t.table_name
          """, userId.trim());
      List<Map<String, Object>> changes = query(connection, """
          SELECT c.project_id, c.request_id, c.description, c.status, c.created_at
            FROM catalog.intake_change c
            JOIN catalog.intake_project p ON p.project_id = c.project_id
            JOIN catalog.intake_user u ON u.user_id = p.user_id
           WHERE lower(u.login_id) = lower(?)
             AND c.status <> 'DISCARDED'
           ORDER BY c.created_at DESC
          """, userId.trim());
      List<Map<String, Object>> deployments = query(connection, """
          SELECT d.database_id, dep.target_environment, dep.target_database, dep.status, dep.deployed_at
            FROM catalog.intake_deployment dep
            JOIN catalog.intake_database d ON d.database_id = dep.database_id
            JOIN catalog.intake_project p ON p.project_id = d.project_id
            JOIN catalog.intake_user u ON u.user_id = p.user_id
           WHERE lower(u.login_id) = lower(?)
           ORDER BY dep.deployed_at DESC
          """, userId.trim());
      for (Map<String, Object> database : databases) {
        String databaseId = String.valueOf(database.get("database_id"));
        database.put("deployments", deployments.stream()
            .filter(item -> databaseId.equals(String.valueOf(item.get("database_id"))))
            .toList());
        database.put("tableList", tables.stream()
            .filter(item -> databaseId.equals(String.valueOf(item.get("database_id"))))
            .toList());
      }
      for (Map<String, Object> project : projects) {
        String projectId = String.valueOf(project.get("project_id"));
        project.put("databases", databases.stream()
            .filter(item -> projectId.equals(String.valueOf(item.get("project_id"))))
            .toList());
        project.put("changes", changes.stream()
            .filter(item -> projectId.equals(String.valueOf(item.get("project_id"))))
            .toList());
      }
      return projects;
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> openChange(String projectId, Map<String, Object> body) {
    String description = required(body, "description");
    UUID project = uuid(projectId, "Project");
    try (Connection connection = connections.openCatalog()) {
      Map<String, Object> row = one(connection, "SELECT project_name, tech_owner_email FROM catalog.intake_project WHERE project_id = ?", project);
      if (row == null) {
        throw new NotFoundException("Project was not found");
      }
      Map<String, Object> open = one(connection, """
          SELECT request_id FROM catalog.intake_change WHERE project_id = ? AND status = 'OPEN'
          """, project);
      if (open != null) {
        throw new BadRequestException("Finish or delete " + open.get("request_id") + " before starting another change");
      }
      String requestId = newRequestId();
      try (PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO catalog.intake_change (request_id, project_id, description, status)
          VALUES (?, ?, ?, 'OPEN')
          """)) {
        statement.setString(1, requestId);
        statement.setObject(2, project);
        statement.setString(3, description);
        statement.executeUpdate();
      }
      record(connection, text(row.get("tech_owner_email")), "CHANGE_STARTED", "project", String.valueOf(row.get("project_name")),
          description, requestId, description);
      return changeView(requestId, description, project.toString(), 0);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public List<Map<String, Object>> openChanges(String projectId) {
    UUID project = uuid(projectId, "Project");
    try (Connection connection = connections.openCatalog()) {
      return query(connection, """
          SELECT request_id, description, created_at,
                 (SELECT count(*) FROM catalog.platform_activity activity
                   WHERE activity.request_id = change.request_id
                     AND activity.action <> 'CHANGE_STARTED') AS work_count
            FROM catalog.intake_change change
           WHERE project_id = ? AND status = 'OPEN'
           ORDER BY created_at DESC
          """, project);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> finishChange(String projectId, String requestId) {
    UUID project = uuid(projectId, "Project");
    try (Connection connection = connections.openCatalog()) {
      Map<String, Object> change = openChangeRow(connection, project, requestId);
      try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE catalog.intake_change
             SET status = 'COMPLETED', closed_at = CURRENT_TIMESTAMP
           WHERE request_id = ? AND project_id = ? AND status = 'OPEN'
          """)) {
        statement.setString(1, requestId);
        statement.setObject(2, project);
        statement.executeUpdate();
      }
      Map<String, Object> projectRow = one(connection, "SELECT project_name FROM catalog.intake_project WHERE project_id = ?", project);
      record(connection, emailOfProject(connection, project), "CHANGE_COMPLETED", "project",
          String.valueOf(projectRow.get("project_name")), "Change request finished", requestId, String.valueOf(change.get("description")));
      Map<String, Object> result = changeView(requestId, String.valueOf(change.get("description")), project.toString(), workCount(connection, requestId));
      result.put("status", "COMPLETED");
      return result;
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public void discardChange(String projectId, String requestId) {
    UUID project = uuid(projectId, "Project");
    try (Connection connection = connections.openCatalog()) {
      openChangeRow(connection, project, requestId);
      if (workCount(connection, requestId) > 0) {
        throw new BadRequestException("This change already has work. Finish it so the audit stays complete.");
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM catalog.platform_activity WHERE request_id = ? AND action = 'CHANGE_STARTED'")) {
        statement.setString(1, requestId);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM catalog.intake_change WHERE request_id = ? AND project_id = ? AND status = 'OPEN'")) {
        statement.setString(1, requestId);
        statement.setObject(2, project);
        statement.executeUpdate();
      }
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  private Map<String, Object> openChangeRow(Connection connection, UUID project, String requestId) throws SQLException {
    Map<String, Object> change = one(connection, """
        SELECT request_id, description FROM catalog.intake_change
         WHERE request_id = ? AND project_id = ? AND status = 'OPEN'
        """, requestId, project);
    if (change == null) {
      throw new NotFoundException("Open change request was not found");
    }
    return change;
  }

  private int workCount(Connection connection, String requestId) throws SQLException {
    Map<String, Object> row = one(connection, """
        SELECT count(*) AS work_count FROM catalog.platform_activity
         WHERE request_id = ? AND action <> 'CHANGE_STARTED'
        """, requestId);
    return row == null ? 0 : ((Number) row.get("work_count")).intValue();
  }

  private static Map<String, Object> changeView(String requestId, String description, String projectId, int workCount) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requestId", requestId);
    result.put("description", description);
    result.put("projectId", projectId);
    result.put("status", "OPEN");
    result.put("workCount", workCount);
    return result;
  }

  public Map<String, Object> promote(String projectId, Map<String, Object> body) {
    String environment = required(body, "environment").toUpperCase(Locale.ROOT);
    UUID project = uuid(projectId, "Project");
    List<String> requested = stringList(body.get("tableIds"));
    List<Map<String, Object>> tables;
    CatalogRegistrar.ProjectContext context;
    Map<String, List<Map<String, Object>>> byDatabase = new LinkedHashMap<>();
    try (Connection connection = connections.openCatalog()) {
      tables = query(connection, """
          SELECT t.table_id, t.table_name, t.ddl, s.schema_name, d.database_id, d.database_name
            FROM catalog.intake_table t
            JOIN catalog.intake_schema s ON s.schema_id = t.schema_id
            JOIN catalog.intake_database d ON d.database_id = s.database_id
           WHERE d.project_id = ?
           ORDER BY d.database_name, s.schema_name, t.table_name
          """, project);
      if (!requested.isEmpty()) {
        tables = tables.stream().filter(table -> requested.contains(String.valueOf(table.get("table_id")))).toList();
      }
      if (tables.isEmpty()) {
        throw new BadRequestException("Select at least one table to promote");
      }
      Map<String, Object> projectRow = one(connection, """
          SELECT p.project_name, p.business_line, p.domain, p.sub_domain, p.edai_req_id,
                 p.tech_owner_email, p.developer_email, p.car_id, u.login_id
            FROM catalog.intake_project p
            JOIN catalog.intake_user u ON u.user_id = p.user_id
           WHERE p.project_id = ?
          """, project);
      context = new CatalogRegistrar.ProjectContext(
          String.valueOf(projectRow.get("project_name")),
          text(projectRow.get("business_line")),
          text(projectRow.get("domain")),
          text(projectRow.get("sub_domain")),
          text(projectRow.get("edai_req_id")),
          text(projectRow.get("tech_owner_email")),
          text(projectRow.get("developer_email")),
          text(projectRow.get("login_id")),
          text(projectRow.get("car_id")));
      for (Map<String, Object> table : tables) {
        byDatabase.computeIfAbsent(String.valueOf(table.get("database_id")), key -> new ArrayList<>()).add(table);
      }
    } catch (SQLException exception) {
      throw sql(exception);
    }
    List<Map<String, Object>> payload = new ArrayList<>();
    for (Map<String, Object> table : tables) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("databaseName", table.get("database_name"));
      item.put("schemaName", table.get("schema_name"));
      item.put("tableName", table.get("table_name"));
      item.put("ddl", table.get("ddl"));
      item.put("project", projectMap(context));
      payload.add(item);
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("environment", environment);
    request.put("cloud", "AWS");
    request.put("tables", payload);
    Map<String, Object> applied = clients.execution("promote", request);
    try (Connection connection = connections.openCatalog()) {
      for (List<Map<String, Object>> group : byDatabase.values()) {
        String sourceName = String.valueOf(group.get(0).get("database_name"));
        UUID database = UUID.fromString(String.valueOf(group.get(0).get("database_id")));
        String targetName = ddl.targetDatabase(sourceName, environment);
        String names = group.stream().map(table -> table.get("schema_name") + "." + table.get("table_name")).reduce((left, right) -> left + ", " + right).orElse("");
        insert(connection, """
            INSERT INTO catalog.intake_deployment (database_id, target_environment, target_database, status, message)
            VALUES (?, ?, ?, 'DEPLOYED', ?) RETURNING deployment_id
            """, database, environment, targetName, names);
        record(connection, emailOfProject(connection, project), "PROMOTED", "table", names,
            "Promoted to " + environment + " as " + targetName, requestId(body), requestDescription(body));
      }
      return applied;
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public List<Map<String, Object>> activity(String userId, String requestId, String action, String q) {
    if (userId == null || userId.isBlank()) {
      throw new BadRequestException("User id is required");
    }
    String email = "";
    try (Connection connection = connections.openCatalog()) {
      Map<String, Object> user = one(connection, """
          SELECT email FROM catalog.intake_user WHERE lower(login_id) = lower(?)
          """, userId.trim());
      if (user != null) {
        email = text(user.get("email"));
      }
    } catch (SQLException exception) {
      throw sql(exception);
    }
    StringBuilder sql = new StringBuilder("""
        SELECT activity_id, request_id, description, action, object_type, object_name, detail, created_at
          FROM catalog.platform_activity
         WHERE lower(user_email) = lower(?)
            OR (? <> '' AND lower(user_email) = lower(?))
        """);
    List<Object> args = new ArrayList<>();
    args.add(userId.trim());
    args.add(email);
    args.add(email);
    if (requestId != null && !requestId.isBlank()) {
      sql.append(" AND request_id ILIKE ?");
      args.add("%" + requestId.trim() + "%");
    }
    if (action != null && !action.isBlank()) {
      sql.append(" AND action = ?");
      args.add(action.trim());
    }
    if (q != null && !q.isBlank()) {
      sql.append(" AND (coalesce(description, '') ILIKE ? OR coalesce(object_name, '') ILIKE ? OR coalesce(detail, '') ILIKE ?)");
      String pattern = "%" + q.trim() + "%";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    sql.append(" ORDER BY created_at DESC LIMIT 200");
    try (Connection connection = connections.openCatalog()) {
      return query(connection, sql.toString(), args.toArray());
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> project(String projectId) {
    try (Connection connection = connections.openCatalog()) {
      return project(connection, uuid(projectId, "Project"));
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> createDatabase(String projectId, Map<String, Object> body) {
    String databaseName = ddl.requireName(required(body, "databaseName"), "Database name");
    ddl.requireDatabaseName(databaseName);
    UUID project = uuid(projectId, "Project");
    try (Connection connection = connections.openCatalog()) {
      require(connection, "SELECT 1 FROM catalog.intake_project WHERE project_id = ?", project, "Project");
      clients.execution("createDatabase", Map.of("databaseName", databaseName));
      UUID id = insert(connection, """
          INSERT INTO catalog.intake_database (project_id, database_name)
          VALUES (?, ?) RETURNING database_id
          """, project, databaseName);
      record(connection, emailOfProject(connection, project), "DATABASE_CREATED", "database", databaseName, "Created in DEV", requestId(body), requestDescription(body));
      return one(connection, "SELECT * FROM catalog.intake_database WHERE database_id = ?", id);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> createSchema(String databaseId, Map<String, Object> body) {
    String schemaName = ddl.requireName(required(body, "schemaName"), "Schema name");
    ddl.rejectPublicSchema(schemaName);
    UUID database = uuid(databaseId, "Database");
    try (Connection connection = connections.openCatalog()) {
      Map<String, Object> row = one(connection, "SELECT * FROM catalog.intake_database WHERE database_id = ?", database);
      if (row == null) {
        throw new NotFoundException("Database was not found");
      }
      String databaseName = String.valueOf(row.get("database_name"));
      clients.execution("createSchema", Map.of("databaseName", databaseName, "schemaName", schemaName));
      UUID id = insert(connection, """
          INSERT INTO catalog.intake_schema (database_id, schema_name)
          VALUES (?, ?) RETURNING schema_id
          """, database, schemaName);
      record(connection, emailOfDatabase(connection, database), "SCHEMA_CREATED", "schema", schemaName, "Created in DEV", requestId(body), requestDescription(body));
      return one(connection, "SELECT * FROM catalog.intake_schema WHERE schema_id = ?", id);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> previewTable(String schemaId, Map<String, Object> body) {
    SchemaRef schema = schemaRef(schemaId);
    String tableName = ddl.requireName(required(body, "tableName"), "Table name");
    String script = ddl.tableDdl(schema.schemaName, tableName, text(body.get("description")), columns(body), indexes(body));
    return Map.of("ddl", script, "schema", schema.schemaName, "table", tableName);
  }

  public Map<String, Object> createDesignedTable(String schemaId, Map<String, Object> body) {
    SchemaRef schema = schemaRef(schemaId);
    EntityModelRequest entity = entityRequest(body, schema.schemaName);
    EntityModelResponse model;
    try {
      ModelingRequest request = new ModelingRequest();
      request.setEntities(List.of(entity));
      model = modeler.generateModels(request).getEntities().get(0);
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException(exception.getMessage());
    }
    String script = model.getCreateTableCql();
    if (model.getIndexCql() != null && !model.getIndexCql().isEmpty()) {
      script = script + "\n" + String.join("\n", model.getIndexCql());
    }
    Long totalRecords = CatalogRegistrar.longOrNull(text(body.get("totalRecord")), "Total record");
    Long recordSize = CatalogRegistrar.longOrNull(text(body.get("recordSizeInBytes")), "Record size");
    Long estimatedBytes = CatalogRegistrar.estimatedBytes(text(body.get("volumeInGBCurrentYear")), totalRecords, recordSize);
    Integer estimatedTps = CatalogRegistrar.tps(text(body.get("averageTps")), text(body.get("peakTps")));
    CatalogRegistrar.ProjectContext context = projectContext(schema.schemaId);
    Map<String, Object> applied = clients.execution("design", Map.of(
        "databaseName", schema.databaseName,
        "schemaName", schema.schemaName,
        "sql", script,
        "registration", designRegistration(schema, model, entity, script, context)));
    try (Connection connection = connections.openCatalog()) {
      UUID tableId = insert(connection, """
          INSERT INTO catalog.intake_table (
              schema_id, table_name, description, ddl, source, data_owner_id, table_context_id)
          VALUES (?, ?, ?, ?, 'DESIGN', ?, ?)
          ON CONFLICT (schema_id, table_name) DO UPDATE SET
              description = EXCLUDED.description,
              ddl = EXCLUDED.ddl,
              data_owner_id = EXCLUDED.data_owner_id,
              table_context_id = EXCLUDED.table_context_id,
              updated_at = CURRENT_TIMESTAMP
          RETURNING table_id
          """, schema.schemaId, model.getTableName(), text(body.get("entityDescription")), script,
          text(applied.get("dataOwnerId")), uuid(String.valueOf(applied.get("tableContextId")), "Catalog context"));
      try (PreparedStatement deleteColumns = connection.prepareStatement("DELETE FROM catalog.intake_column WHERE table_id = ?")) {
        deleteColumns.setObject(1, tableId);
        deleteColumns.executeUpdate();
      }
      try (PreparedStatement deleteIndexes = connection.prepareStatement("DELETE FROM catalog.intake_index WHERE table_id = ?")) {
        deleteIndexes.setObject(1, tableId);
        deleteIndexes.executeUpdate();
      }
      saveDesignedColumns(connection, tableId, entity.getFields(), model);
      saveDesignedIndexes(connection, tableId, model);
      Map<String, Object> created = table(connection, tableId);
      created.put("data_owner_id", text(applied.get("dataOwnerId")));
      created.put("table_context_id", text(applied.get("tableContextId")));
      created.put("catalog_table_id", text(applied.get("catalogTableId")));
      created.put("partitionKey", model.getPartitionKey());
      created.put("warnings", model.getWarnings());
      record(connection, emailOfSchema(connection, schema.schemaId), "TABLE_DESIGNED", "table",
          schema.schemaName + "." + model.getTableName(), "Designed and created in DEV", requestId(body), requestDescription(body));
      return created;
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> createTable(String schemaId, Map<String, Object> body) {
    SchemaRef schema = schemaRef(schemaId);
    String tableName = ddl.requireName(required(body, "tableName"), "Table name");
    List<Map<String, Object>> columns = columns(body);
    List<Map<String, Object>> indexes = indexes(body);
    String script = ddl.tableDdl(schema.schemaName, tableName, text(body.get("description")), columns, indexes);
    CatalogRegistrar.ProjectContext context = projectContext(schema.schemaId);
    clients.execution("design", Map.of(
        "databaseName", schema.databaseName,
        "schemaName", schema.schemaName,
        "sql", script,
        "registration", simpleRegistration(schema, tableName, script, columns, indexes, context)));
    try (Connection connection = connections.openCatalog()) {
      UUID tableId = insert(connection, """
          INSERT INTO catalog.intake_table (schema_id, table_name, description, ddl, source)
          VALUES (?, ?, ?, ?, 'DESIGN') RETURNING table_id
          """, schema.schemaId, tableName, text(body.get("description")), script);
      saveColumns(connection, tableId, columns);
      saveIndexes(connection, tableId, schema.schemaName, tableName, indexes);
      return table(connection, tableId);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> addColumn(String tableId, Map<String, Object> body) {
    UUID id = uuid(tableId, "Table");
    TableRef table;
    String actor;
    try (Connection connection = connections.openCatalog()) {
      table = tableRef(connection, id);
      actor = emailOfTable(connection, id);
    } catch (SQLException exception) {
      throw sql(exception);
    }
    String statement = ddl.addColumnDdl(table.schemaName, table.tableName, body);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("databaseName", table.databaseName);
    payload.put("schemaName", table.schemaName);
    payload.put("tableName", table.tableName);
    payload.put("sql", statement);
    payload.put("name", ddl.requireName(required(body, "name"), "Column name"));
    payload.put("type", String.valueOf(body.get("type")));
    payload.put("nullable", body.get("nullable") == null || Boolean.TRUE.equals(body.get("nullable")));
    payload.put("actor", actor);
    payload.put("cloud", "AWS");
    payload.put("environment", "DEV");
    clients.execution("addColumn", payload);
    try (Connection connection = connections.openCatalog()) {
      int position = count(connection, "SELECT count(*) FROM catalog.intake_column WHERE table_id = ?", id) + 1;
      try (PreparedStatement statementInsert = connection.prepareStatement("""
          INSERT INTO catalog.intake_column
              (table_id, column_name, data_type, is_nullable, is_primary_key, ordinal_position, default_expression)
          VALUES (?, ?, ?, ?, FALSE, ?, ?)
          """)) {
        statementInsert.setObject(1, id);
        statementInsert.setString(2, ddl.requireName(required(body, "name"), "Column name"));
        statementInsert.setString(3, String.valueOf(body.get("type")));
        statementInsert.setBoolean(4, body.get("nullable") == null || Boolean.TRUE.equals(body.get("nullable")));
        statementInsert.setInt(5, position);
        statementInsert.setString(6, text(body.get("defaultExpression")));
        statementInsert.executeUpdate();
      }
      appendDdl(connection, id, statement);
      record(connection, emailOfTable(connection, id), "COLUMN_ADDED", "column", text(body.get("name")), table.tableName, requestId(body), requestDescription(body));
      return table(connection, id);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> addIndex(String tableId, Map<String, Object> body) {
    UUID id = uuid(tableId, "Table");
    TableRef table;
    String actor;
    try (Connection connection = connections.openCatalog()) {
      table = tableRef(connection, id);
      actor = emailOfTable(connection, id);
    } catch (SQLException exception) {
      throw sql(exception);
    }
    String statement = ddl.indexDdl(table.schemaName, table.tableName, body);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("databaseName", table.databaseName);
    payload.put("schemaName", table.schemaName);
    payload.put("tableName", table.tableName);
    payload.put("sql", statement);
    payload.put("indexName", ddl.requireName(required(body, "name"), "Index name"));
    payload.put("columns", List.of(String.valueOf(body.get("columns")).split(",")));
    payload.put("unique", Boolean.TRUE.equals(body.get("unique")));
    payload.put("actor", actor);
    payload.put("cloud", "AWS");
    payload.put("environment", "DEV");
    clients.execution("addIndex", payload);
    try (Connection connection = connections.openCatalog()) {
      try (PreparedStatement insert = connection.prepareStatement("""
          INSERT INTO catalog.intake_index (table_id, index_name, column_names, is_unique, ddl)
          VALUES (?, ?, ?, ?, ?)
          """)) {
        insert.setObject(1, id);
        insert.setString(2, ddl.requireName(required(body, "name"), "Index name"));
        insert.setString(3, String.valueOf(body.get("columns")));
        insert.setBoolean(4, Boolean.TRUE.equals(body.get("unique")));
        insert.setString(5, statement);
        insert.executeUpdate();
      }
      appendDdl(connection, id, statement);
      record(connection, emailOfTable(connection, id), "INDEX_ADDED", "index", text(body.get("name")), table.tableName, requestId(body), requestDescription(body));
      return table(connection, id);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> previewDdl(String schemaId, Map<String, Object> body) {
    SchemaRef schema = schemaRef(schemaId);
    List<Map<String, Object>> statements = ddl.previewScript(required(body, "sql"), schema.schemaName);
    return Map.of("schema", schema.schemaName, "database", schema.databaseName, "statements", statements);
  }

  public Map<String, Object> executeDdl(String schemaId, Map<String, Object> body) {
    SchemaRef schema = schemaRef(schemaId);
    List<Map<String, Object>> statements = ddl.previewScript(required(body, "sql"), schema.schemaName);
    CatalogRegistrar.ProjectContext context = projectContext(schema.schemaId);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("databaseName", schema.databaseName);
    request.put("schemaName", schema.schemaName);
    request.put("sql", required(body, "sql"));
    request.put("cloud", "AWS");
    request.put("environment", "DEV");
    request.put("project", projectMap(context));
    Map<String, Object> applied = clients.execution("executeDdl", request);
    try (Connection connection = connections.openCatalog()) {
      Map<String, Map<String, Object>> registered = registeredTables(applied);
      Map<String, UUID> intakeIds = new LinkedHashMap<>();
      for (Map<String, Object> statement : statements) {
        String sqlText = String.valueOf(statement.get("sql"));
        String kind = String.valueOf(statement.get("kind"));
        if ("TABLE".equals(kind)) {
          DdlBuilder.ParsedTable parsed = ddl.parseCreateTable(sqlText);
          Map<String, Object> catalogRow = registered.get(parsed.name().toLowerCase(Locale.ROOT));
          if (catalogRow == null) {
            throw new BadRequestException("Catalog registration did not return " + parsed.name());
          }
          String script = sqlText.endsWith(";") ? sqlText : sqlText + ";";
          UUID tableId = insert(connection, """
              INSERT INTO catalog.intake_table (
                  schema_id, table_name, ddl, source, data_owner_id, table_context_id)
              VALUES (?, ?, ?, 'UPLOAD', ?, ?)
              ON CONFLICT (schema_id, table_name) DO UPDATE SET
                  ddl = EXCLUDED.ddl,
                  data_owner_id = EXCLUDED.data_owner_id,
                  table_context_id = EXCLUDED.table_context_id,
                  updated_at = CURRENT_TIMESTAMP
              RETURNING table_id
              """, schema.schemaId, parsed.name(), script, text(catalogRow.get("dataOwnerId")),
              uuid(String.valueOf(catalogRow.get("tableContextId")), "Catalog context"));
          replaceIntakeColumns(connection, tableId, parsed.columns());
          try (PreparedStatement deleteIndexes = connection.prepareStatement("DELETE FROM catalog.intake_index WHERE table_id = ?")) {
            deleteIndexes.setObject(1, tableId);
            deleteIndexes.executeUpdate();
          }
          intakeIds.put(parsed.name().toLowerCase(Locale.ROOT), tableId);
        } else if ("INDEX".equals(kind)) {
          DdlBuilder.ParsedIndex parsed = ddl.parseCreateIndex(sqlText);
          UUID tableId = intakeIds.get(parsed.tableName().toLowerCase(Locale.ROOT));
          if (tableId == null) {
            tableId = intakeTableId(connection, schema.schemaId, parsed.tableName());
          }
          if (tableId == null) {
            throw new BadRequestException("Create " + parsed.tableName() + " before adding an index");
          }
          saveIntakeIndex(connection, tableId, parsed, sqlText);
        } else if ("COLUMN".equals(kind)) {
          DdlBuilder.AddedColumn parsed = ddl.parseAddColumn(sqlText);
          UUID tableId = intakeIds.get(parsed.tableName().toLowerCase(Locale.ROOT));
          if (tableId == null) {
            tableId = intakeTableId(connection, schema.schemaId, parsed.tableName());
          }
          if (tableId == null) {
            throw new BadRequestException("Create " + parsed.tableName() + " before adding a column");
          }
          saveIntakeColumn(connection, tableId, parsed);
        }
      }
      record(connection, emailOfSchema(connection, schema.schemaId), "DDL_EXECUTED", "schema", schema.schemaName,
          statements.size() + " statement(s)", requestId(body), requestDescription(body));
      return applied;
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  public Map<String, Object> deploy(String databaseId, Map<String, Object> body) {
    UUID database = uuid(databaseId, "Database");
    try (Connection connection = connections.openCatalog()) {
      Map<String, Object> source = one(connection, "SELECT project_id FROM catalog.intake_database WHERE database_id = ?", database);
      if (source == null) {
        throw new NotFoundException("Database was not found");
      }
      List<Map<String, Object>> tables = query(connection, """
          SELECT t.table_id
            FROM catalog.intake_table t
            JOIN catalog.intake_schema s ON s.schema_id = t.schema_id
           WHERE s.database_id = ?
          """, database);
      List<String> ids = tables.stream().map(table -> String.valueOf(table.get("table_id"))).toList();
      Map<String, Object> request = new LinkedHashMap<>(body);
      request.put("tableIds", ids);
      return promote(String.valueOf(source.get("project_id")), request);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  private Map<String, Object> project(Connection connection, UUID projectId) throws SQLException {
    Map<String, Object> project = one(connection, """
        SELECT p.project_id, p.project_name, p.description, p.business_line, p.tech_owner_email, p.status,
               p.edai_req_id, p.fund_type, p.fund_value, p.sub_domain, p.domain, p.developer_email,
               p.car_id, p.car_name, p.business_line_owner, p.support_assignment_group,
               p.exp_dev_date, p.exp_it_date, p.uat_date, p.prod_date,
               u.email, u.full_name
          FROM catalog.intake_project p
          JOIN catalog.intake_user u ON u.user_id = p.user_id
         WHERE p.project_id = ?
        """, projectId);
    if (project == null) {
      throw new NotFoundException("Project was not found");
    }
    List<Map<String, Object>> databases = query(connection, """
        SELECT database_id, database_name, environment, status, created_at
          FROM catalog.intake_database
         WHERE project_id = ?
         ORDER BY database_name
        """, projectId);
    for (Map<String, Object> database : databases) {
      database.put("schemas", schemas(connection, UUID.fromString(String.valueOf(database.get("database_id")))));
      database.put("deployments", query(connection, """
          SELECT deployment_id, target_environment, target_database, status, deployed_at
            FROM catalog.intake_deployment
           WHERE database_id = ?
           ORDER BY deployed_at DESC
          """, UUID.fromString(String.valueOf(database.get("database_id")))));
    }
    project.put("databases", databases);
    return project;
  }

  private List<Map<String, Object>> schemas(Connection connection, UUID databaseId) throws SQLException {
    List<Map<String, Object>> schemas = query(connection, """
        SELECT schema_id, schema_name, created_at
          FROM catalog.intake_schema
         WHERE database_id = ?
         ORDER BY schema_name
        """, databaseId);
    for (Map<String, Object> schema : schemas) {
      UUID schemaId = UUID.fromString(String.valueOf(schema.get("schema_id")));
      List<Map<String, Object>> tables = query(connection, """
          SELECT table_id, table_name, description, source, status, updated_at
            FROM catalog.intake_table
           WHERE schema_id = ?
           ORDER BY table_name
          """, schemaId);
      for (Map<String, Object> table : tables) {
        UUID tableId = UUID.fromString(String.valueOf(table.get("table_id")));
        table.put("columns", query(connection, """
            SELECT column_name, data_type, is_nullable, is_primary_key, ordinal_position
              FROM catalog.intake_column WHERE table_id = ? ORDER BY ordinal_position
            """, tableId));
        table.put("indexes", query(connection, """
            SELECT index_name, column_names, is_unique FROM catalog.intake_index WHERE table_id = ? ORDER BY index_name
            """, tableId));
      }
      schema.put("tables", tables);
    }
    return schemas;
  }

  private Map<String, Object> table(Connection connection, UUID tableId) throws SQLException {
    Map<String, Object> table = one(connection, """
        SELECT t.table_id, t.table_name, t.description, t.ddl, t.source, t.status,
               s.schema_name, d.database_name
          FROM catalog.intake_table t
          JOIN catalog.intake_schema s ON s.schema_id = t.schema_id
          JOIN catalog.intake_database d ON d.database_id = s.database_id
         WHERE t.table_id = ?
        """, tableId);
    if (table == null) {
      throw new NotFoundException("Table was not found");
    }
    table.put("columns", query(connection, """
        SELECT column_name, data_type, is_nullable, is_primary_key, ordinal_position
          FROM catalog.intake_column WHERE table_id = ? ORDER BY ordinal_position
        """, tableId));
    table.put("indexes", query(connection, """
        SELECT index_name, column_names, is_unique FROM catalog.intake_index WHERE table_id = ?
        """, tableId));
    return table;
  }

  private CatalogRegistrar.ProjectContext projectContext(Connection connection, UUID schemaId) throws SQLException {
    Map<String, Object> project = one(connection, """
        SELECT p.project_name, p.business_line, p.domain, p.sub_domain, p.edai_req_id,
               p.tech_owner_email, p.developer_email, p.car_id, u.login_id
          FROM catalog.intake_schema s
          JOIN catalog.intake_database d ON d.database_id = s.database_id
          JOIN catalog.intake_project p ON p.project_id = d.project_id
          JOIN catalog.intake_user u ON u.user_id = p.user_id
         WHERE s.schema_id = ?
        """, schemaId);
    if (project == null) {
      throw new NotFoundException("Schema was not found");
    }
    return new CatalogRegistrar.ProjectContext(
        String.valueOf(project.get("project_name")),
        text(project.get("business_line")),
        text(project.get("domain")),
        text(project.get("sub_domain")),
        text(project.get("edai_req_id")),
        text(project.get("tech_owner_email")),
        text(project.get("developer_email")),
        text(project.get("login_id")),
        text(project.get("car_id")));
  }

  private CatalogRegistrar.ProjectContext projectContext(UUID schemaId) {
    try (Connection connection = connections.openCatalog()) {
      return projectContext(connection, schemaId);
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  private Map<String, Object> projectMap(CatalogRegistrar.ProjectContext context) {
    Map<String, Object> project = new LinkedHashMap<>();
    project.put("projectName", context.projectName());
    project.put("businessLine", context.businessLine());
    project.put("domain", context.domain());
    project.put("subDomain", context.subDomain());
    project.put("edaiReqId", context.edaiReqId());
    project.put("techOwnerEmail", context.techOwnerEmail());
    project.put("developerEmail", context.developerEmail());
    project.put("platformUserId", context.platformUserId());
    project.put("carId", context.carId());
    return project;
  }

  private Map<String, Object> designRegistration(
      SchemaRef schema, EntityModelResponse model, EntityModelRequest entity, String script, CatalogRegistrar.ProjectContext context) {
    Set<String> primary = new HashSet<>();
    model.getPartitionKey().forEach(name -> primary.add(name.toLowerCase(Locale.ROOT)));
    model.getClusteringKeys().forEach(key -> primary.add(key.getField().toLowerCase(Locale.ROOT)));
    List<Map<String, Object>> columns = new ArrayList<>();
    for (FieldMetadata field : entity.getFields()) {
      if (field.getName() == null || field.getName().isBlank()) {
        continue;
      }
      boolean key = primary.contains(field.getName().toLowerCase(Locale.ROOT));
      Map<String, Object> column = new LinkedHashMap<>();
      column.put("name", field.getName());
      column.put("type", field.getDataType() == null || field.getDataType().isBlank() ? "text" : field.getDataType());
      column.put("nullable", !key);
      column.put("primaryKey", key);
      columns.add(column);
    }
    List<Map<String, Object>> indexes = new ArrayList<>();
    for (var recommendation : model.getIndexes()) {
      Map<String, Object> index = new LinkedHashMap<>();
      index.put("tableName", model.getTableName());
      index.put("indexName", limitName(model.getTableName() + "_" + recommendation.getField() + "_idx"));
      index.put("columns", List.of(recommendation.getField()));
      index.put("unique", false);
      indexes.add(index);
    }
    Map<String, Object> registration = new LinkedHashMap<>();
    registration.put("cloud", "AWS");
    registration.put("environment", "DEV");
    registration.put("databaseName", schema.databaseName);
    registration.put("schemaName", schema.schemaName);
    registration.put("tableName", model.getTableName());
    registration.put("ddl", script);
    registration.put("partitionKey", model.getPartitionKey());
    registration.put("columns", columns);
    registration.put("indexes", indexes);
    registration.put("project", projectMap(context));
    return registration;
  }

  private Map<String, Object> simpleRegistration(
      SchemaRef schema, String tableName, String script, List<Map<String, Object>> columns, List<Map<String, Object>> indexes,
      CatalogRegistrar.ProjectContext context) {
    List<String> partition = new ArrayList<>();
    List<Map<String, Object>> columnPayload = new ArrayList<>();
    for (Map<String, Object> column : columns) {
      boolean primary = Boolean.TRUE.equals(column.get("primaryKey"));
      if (primary) {
        partition.add(String.valueOf(column.get("name")));
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", column.get("name"));
      item.put("type", column.get("type"));
      item.put("nullable", !primary && (column.get("nullable") == null || Boolean.TRUE.equals(column.get("nullable"))));
      item.put("primaryKey", primary);
      columnPayload.add(item);
    }
    List<Map<String, Object>> indexPayload = new ArrayList<>();
    for (Map<String, Object> index : indexes) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("tableName", tableName);
      item.put("indexName", index.get("name"));
      item.put("columns", List.of(String.valueOf(index.get("columns")).split(",")));
      item.put("unique", Boolean.TRUE.equals(index.get("unique")));
      indexPayload.add(item);
    }
    Map<String, Object> registration = new LinkedHashMap<>();
    registration.put("cloud", "AWS");
    registration.put("environment", "DEV");
    registration.put("databaseName", schema.databaseName);
    registration.put("schemaName", schema.schemaName);
    registration.put("tableName", tableName);
    registration.put("ddl", script);
    registration.put("partitionKey", partition);
    registration.put("columns", columnPayload);
    registration.put("indexes", indexPayload);
    registration.put("project", projectMap(context));
    return registration;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Map<String, Object>> registeredTables(Map<String, Object> applied) {
    Map<String, Map<String, Object>> registered = new LinkedHashMap<>();
    Object value = applied.get("tables");
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          registered.put(String.valueOf(map.get("tableName")).toLowerCase(Locale.ROOT), (Map<String, Object>) map);
        }
      }
    }
    return registered;
  }

  private UUID intakeTableId(Connection connection, UUID schemaId, String tableName) throws SQLException {
    Map<String, Object> row = one(connection, """
        SELECT table_id FROM catalog.intake_table WHERE schema_id = ? AND lower(table_name) = lower(?)
        """, schemaId, tableName);
    return row == null ? null : UUID.fromString(String.valueOf(row.get("table_id")));
  }

  private void replaceIntakeColumns(Connection connection, UUID tableId, List<DdlBuilder.ParsedColumn> columns) throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM catalog.intake_column WHERE table_id = ?")) {
      delete.setObject(1, tableId);
      delete.executeUpdate();
    }
    int position = 1;
    for (DdlBuilder.ParsedColumn column : columns) {
      try (PreparedStatement insert = connection.prepareStatement("""
          INSERT INTO catalog.intake_column
              (table_id, column_name, data_type, is_nullable, is_primary_key, ordinal_position)
          VALUES (?, ?, ?, ?, ?, ?)
          """)) {
        insert.setObject(1, tableId);
        insert.setString(2, column.name());
        insert.setString(3, column.type());
        insert.setBoolean(4, column.nullable());
        insert.setBoolean(5, column.primaryKey());
        insert.setInt(6, position++);
        insert.executeUpdate();
      }
    }
  }

  private void saveIntakeIndex(Connection connection, UUID tableId, DdlBuilder.ParsedIndex index, String sqlText) throws SQLException {
    String script = sqlText.endsWith(";") ? sqlText : sqlText + ";";
    try (PreparedStatement delete = connection.prepareStatement(
        "DELETE FROM catalog.intake_index WHERE table_id = ? AND index_name = ?")) {
      delete.setObject(1, tableId);
      delete.setString(2, index.indexName());
      delete.executeUpdate();
    }
    try (PreparedStatement insert = connection.prepareStatement("""
        INSERT INTO catalog.intake_index (table_id, index_name, column_names, is_unique, ddl)
        VALUES (?, ?, ?, ?, ?)
        """)) {
      insert.setObject(1, tableId);
      insert.setString(2, index.indexName());
      insert.setString(3, String.join(", ", index.columns()));
      insert.setBoolean(4, index.unique());
      insert.setString(5, script);
      insert.executeUpdate();
    }
  }

  private void saveIntakeColumn(Connection connection, UUID tableId, DdlBuilder.AddedColumn column) throws SQLException {
    Map<String, Object> existing = one(connection, """
        SELECT column_id FROM catalog.intake_column WHERE table_id = ? AND lower(column_name) = lower(?)
        """, tableId, column.name());
    if (existing != null) {
      try (PreparedStatement update = connection.prepareStatement("""
          UPDATE catalog.intake_column
             SET data_type = ?, is_nullable = ?
           WHERE table_id = ? AND lower(column_name) = lower(?)
          """)) {
        update.setString(1, column.type());
        update.setBoolean(2, column.nullable());
        update.setObject(3, tableId);
        update.setString(4, column.name());
        update.executeUpdate();
      }
      return;
    }
    int position = count(connection, "SELECT count(*) FROM catalog.intake_column WHERE table_id = ?", tableId) + 1;
    try (PreparedStatement insert = connection.prepareStatement("""
        INSERT INTO catalog.intake_column
            (table_id, column_name, data_type, is_nullable, is_primary_key, ordinal_position)
        VALUES (?, ?, ?, ?, FALSE, ?)
        """)) {
      insert.setObject(1, tableId);
      insert.setString(2, column.name());
      insert.setString(3, column.type());
      insert.setBoolean(4, column.nullable());
      insert.setInt(5, position);
      insert.executeUpdate();
    }
  }

  private void saveColumns(Connection connection, UUID tableId, List<Map<String, Object>> columns) throws SQLException {
    int position = 1;
    for (Map<String, Object> column : columns) {
      boolean primary = Boolean.TRUE.equals(column.get("primaryKey"));
      try (PreparedStatement insert = connection.prepareStatement("""
          INSERT INTO catalog.intake_column
              (table_id, column_name, data_type, is_nullable, is_primary_key, ordinal_position, default_expression)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """)) {
        insert.setObject(1, tableId);
        insert.setString(2, String.valueOf(column.get("name")).trim());
        insert.setString(3, String.valueOf(column.get("type")).trim());
        insert.setBoolean(4, !primary && (column.get("nullable") == null || Boolean.TRUE.equals(column.get("nullable"))));
        insert.setBoolean(5, primary);
        insert.setInt(6, position++);
        insert.setString(7, text(column.get("defaultExpression")));
        insert.executeUpdate();
      }
    }
  }

  private void saveIndexes(Connection connection, UUID tableId, String schema, String table, List<Map<String, Object>> indexes) throws SQLException {
    for (Map<String, Object> index : indexes) {
      try (PreparedStatement insert = connection.prepareStatement("""
          INSERT INTO catalog.intake_index (table_id, index_name, column_names, is_unique, ddl)
          VALUES (?, ?, ?, ?, ?)
          """)) {
        insert.setObject(1, tableId);
        insert.setString(2, String.valueOf(index.get("name")).trim());
        insert.setString(3, String.valueOf(index.get("columns")));
        insert.setBoolean(4, Boolean.TRUE.equals(index.get("unique")));
        insert.setString(5, ddl.indexDdl(schema, table, index));
        insert.executeUpdate();
      }
    }
  }

  private void appendDdl(Connection connection, UUID tableId, String statement) throws SQLException {
    try (PreparedStatement update = connection.prepareStatement("""
        UPDATE catalog.intake_table
           SET ddl = ddl || E'\n\n' || ?, updated_at = CURRENT_TIMESTAMP
         WHERE table_id = ?
        """)) {
      update.setString(1, statement);
      update.setObject(2, tableId);
      update.executeUpdate();
    }
  }

  private void executeScript(Connection connection, String schema, List<String> statements) throws SQLException {
    execute(connection, "SET search_path TO " + quote(schema));
    for (String statement : statements) {
      String trimmed = statement.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith("create schema")) {
        execute(connection, trimmed.endsWith(";") ? trimmed : trimmed + ";");
      } else {
        execute(connection, trimmed);
      }
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

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private SchemaRef schemaRef(String schemaId) {
    UUID id = uuid(schemaId, "Schema");
    try (Connection connection = connections.openCatalog()) {
      Map<String, Object> row = one(connection, """
          SELECT s.schema_id, s.schema_name, d.database_name
            FROM catalog.intake_schema s
            JOIN catalog.intake_database d ON d.database_id = s.database_id
           WHERE s.schema_id = ?
          """, id);
      if (row == null) {
        throw new NotFoundException("Schema was not found");
      }
      return new SchemaRef(id, String.valueOf(row.get("schema_name")), String.valueOf(row.get("database_name")));
    } catch (SQLException exception) {
      throw sql(exception);
    }
  }

  private TableRef tableRef(Connection connection, UUID tableId) throws SQLException {
    Map<String, Object> row = one(connection, """
        SELECT t.table_name, s.schema_name, d.database_name
          FROM catalog.intake_table t
          JOIN catalog.intake_schema s ON s.schema_id = t.schema_id
          JOIN catalog.intake_database d ON d.database_id = s.database_id
         WHERE t.table_id = ?
        """, tableId);
    if (row == null) {
      throw new NotFoundException("Table was not found");
    }
    return new TableRef(String.valueOf(row.get("table_name")), String.valueOf(row.get("schema_name")), String.valueOf(row.get("database_name")));
  }

  private UUID findUser(Connection connection, String email) throws SQLException {
    Map<String, Object> row = one(connection, "SELECT user_id FROM catalog.intake_user WHERE lower(email) = lower(?)", email);
    return row == null ? null : UUID.fromString(String.valueOf(row.get("user_id")));
  }

  private void require(Connection connection, String sql, UUID id, String label) throws SQLException {
    if (one(connection, sql, id) == null) {
      throw new NotFoundException(label + " was not found");
    }
  }

  private UUID insert(Connection connection, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private int count(Connection connection, String sql, UUID id) throws SQLException {
    Map<String, Object> row = one(connection, sql, id);
    return row == null ? 0 : ((Number) row.values().iterator().next()).intValue();
  }

  private Map<String, Object> one(Connection connection, String sql, Object... args) throws SQLException {
    List<Map<String, Object>> rows = query(connection, sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private List<Map<String, Object>> query(Connection connection, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet rows = statement.executeQuery()) {
        List<Map<String, Object>> result = new ArrayList<>();
        int columns = rows.getMetaData().getColumnCount();
        while (rows.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= columns; i++) {
            Object value = rows.getObject(i);
            row.put(rows.getMetaData().getColumnLabel(i), value instanceof UUID uuid ? uuid.toString() : value);
          }
          result.add(row);
        }
        return result;
      }
    }
  }

  private static void bind(PreparedStatement statement, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      statement.setObject(i + 1, args[i]);
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> columns(Map<String, Object> body) {
    Object value = body.get("columns");
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> indexes(Map<String, Object> body) {
    Object value = body.get("indexes");
    return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private void saveDesignedColumns(
      Connection connection, UUID tableId, List<FieldMetadata> fields, EntityModelResponse model) throws SQLException {
    Set<String> primary = new HashSet<>();
    model.getPartitionKey().forEach(name -> primary.add(name.toLowerCase(Locale.ROOT)));
    model.getClusteringKeys().forEach(key -> primary.add(key.getField().toLowerCase(Locale.ROOT)));
    int position = 1;
    for (FieldMetadata field : fields) {
      if (field.getName() == null || field.getName().isBlank()) {
        continue;
      }
      boolean key = primary.contains(field.getName().toLowerCase(Locale.ROOT));
      try (PreparedStatement insert = connection.prepareStatement("""
          INSERT INTO catalog.intake_column
              (table_id, column_name, data_type, is_nullable, is_primary_key, ordinal_position)
          VALUES (?, ?, ?, ?, ?, ?)
          """)) {
        insert.setObject(1, tableId);
        insert.setString(2, field.getName());
        insert.setString(3, field.getDataType() == null || field.getDataType().isBlank() ? "text" : field.getDataType());
        insert.setBoolean(4, !key);
        insert.setBoolean(5, key);
        insert.setInt(6, position++);
        insert.executeUpdate();
      }
    }
  }

  private void saveDesignedIndexes(Connection connection, UUID tableId, EntityModelResponse model) throws SQLException {
    int indexNumber = 0;
    for (var recommendation : model.getIndexes()) {
      String ddlText = model.getIndexCql() != null && indexNumber < model.getIndexCql().size()
          ? model.getIndexCql().get(indexNumber) : recommendation.getField();
      indexNumber++;
      try (PreparedStatement insert = connection.prepareStatement("""
          INSERT INTO catalog.intake_index (table_id, index_name, column_names, is_unique, ddl)
          VALUES (?, ?, ?, FALSE, ?)
          """)) {
        insert.setObject(1, tableId);
        insert.setString(2, limitName(model.getTableName() + "_" + recommendation.getField() + "_idx"));
        insert.setString(3, recommendation.getField());
        insert.setString(4, ddlText);
        insert.executeUpdate();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private EntityModelRequest entityRequest(Map<String, Object> body, String schemaName) {
    EntityModelRequest entity = new EntityModelRequest();
    entity.setEntityName(required(body, "entityName"));
    entity.setDescription(firstText(body, "entityDescription", "description"));
    entity.setKeyspace(schemaName);
    List<FieldMetadata> fields = new ArrayList<>();
    Object rawFields = body.get("fields");
    if (rawFields instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> map)) {
          continue;
        }
        Map<String, Object> field = (Map<String, Object>) map;
        FieldMetadata metadata = new FieldMetadata();
        metadata.setName(text(field.get("name")));
        metadata.setDataType(text(field.get("dataType")));
        metadata.setDescription(text(field.get("description")));
        metadata.setCardinality(Cardinality.fromString(text(field.get("cardinality"))));
        metadata.setBusinessKey(Boolean.TRUE.equals(field.get("businessKey")));
        metadata.setMutable(Boolean.TRUE.equals(field.get("mutable")));
        metadata.setTenantField(Boolean.TRUE.equals(field.get("tenantField")));
        metadata.setTimeField(Boolean.TRUE.equals(field.get("timeField")));
        fields.add(metadata);
      }
    }
    entity.setFields(fields);
    List<AccessPattern> patterns = new ArrayList<>();
    Object rawPatterns = body.get("accessPatterns");
    if (rawPatterns instanceof List<?> list) {
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> map)) {
          continue;
        }
        Map<String, Object> pattern = (Map<String, Object>) map;
        AccessPattern accessPattern = new AccessPattern();
        accessPattern.setName(text(pattern.get("name")));
        accessPattern.setDescription(text(pattern.get("description")));
        accessPattern.setCardinality(Cardinality.fromString(text(pattern.get("cardinality"))));
        List<PatternField> filters = new ArrayList<>();
        Object rawFilters = pattern.get("filters");
        if (rawFilters instanceof List<?> filterList) {
          for (Object filterItem : filterList) {
            if (!(filterItem instanceof Map<?, ?> filterMap)) {
              continue;
            }
            PatternField filter = new PatternField();
            filter.setField(text(filterMap.get("field")));
            filter.setType(FilterType.fromString(text(filterMap.get("type"))));
            filters.add(filter);
          }
        }
        accessPattern.setFilters(filters);
        List<SortField> sorts = new ArrayList<>();
        Object rawSorts = pattern.get("sortFields");
        if (rawSorts instanceof List<?> sortList) {
          for (Object sortItem : sortList) {
            if (!(sortItem instanceof Map<?, ?> sortMap)) {
              continue;
            }
            SortField sort = new SortField();
            sort.setField(text(sortMap.get("field")));
            sort.setDirection(text(sortMap.get("direction")));
            sorts.add(sort);
          }
        }
        accessPattern.setSortFields(sorts);
        patterns.add(accessPattern);
      }
    }
    entity.setAccessPatterns(patterns);
    ConstraintSettings constraints = new ConstraintSettings();
    constraints.setKeyspace(schemaName);
    constraints.setPartitionSizeExpectation(PartitionSizeExpectation.MEDIUM);
    constraints.setQueryVolume(QueryVolume.READ_HEAVY);
    constraints.setMultiTenant(fields.stream().anyMatch(FieldMetadata::isTenantField));
    constraints.setTimeSeries(fields.stream().anyMatch(FieldMetadata::isTimeField));
    entity.setConstraints(constraints);
    return entity;
  }

  private static String firstRequired(Map<String, Object> body, String first, String second) {
    String value = firstText(body, first, second);
    if (value.isBlank()) {
      throw new BadRequestException(first + " is required");
    }
    return value;
  }

  private static String firstText(Map<String, Object> body, String first, String second) {
    String value = text(body.get(first));
    return value.isBlank() ? text(body.get(second)) : value;
  }

  private static BigDecimal decimalOrNull(Object value) {
    String text = text(value);
    if (text.isBlank()) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException exception) {
      throw new BadRequestException("Fund value must be a number");
    }
  }

  private static String dateOrNull(Object value) {
    String text = text(value);
    return text.isBlank() ? null : text;
  }

  private static String limitName(String value) {
    return value.length() <= 63 ? value : value.substring(0, 63);
  }

  private void record(
      Connection connection,
      String email,
      String action,
      String objectType,
      String objectName,
      String detail,
      String requestId,
      String description)
      throws SQLException {
    if (email == null || email.isBlank()) {
      return;
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO catalog.platform_activity
            (user_email, action, object_type, object_name, detail, request_id, description)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, email);
      statement.setString(2, action);
      statement.setString(3, objectType);
      statement.setString(4, objectName);
      statement.setString(5, detail);
      statement.setString(6, blankToNull(requestId));
      statement.setString(7, blankToNull(description));
      statement.executeUpdate();
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String assignOdpReqId(Connection connection, String supplied) throws SQLException {
    if (supplied.matches("ODP-[A-Z0-9]{8}") && !odpReqIdTaken(connection, supplied)) {
      return supplied;
    }
    for (int attempt = 0; attempt < 5; attempt++) {
      String id = "ODP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
      if (!odpReqIdTaken(connection, id)) {
        return id;
      }
    }
    throw new BadRequestException("Could not assign an ODP Req ID");
  }

  private boolean odpReqIdTaken(Connection connection, String id) throws SQLException {
    return one(connection, "SELECT project_id FROM catalog.intake_project WHERE edai_req_id = ?", id) != null;
  }

  private static String newRequestId() {
    return "REQ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private static String requestId(Map<String, Object> body) {
    return text(body.get("requestId"));
  }

  private static String requestDescription(Map<String, Object> body) {
    return text(body.get("requestDescription"));
  }

  private String emailOfProject(Connection connection, UUID projectId) throws SQLException {
    Map<String, Object> row = one(connection, """
        SELECT u.login_id
          FROM catalog.intake_project p
          JOIN catalog.intake_user u ON u.user_id = p.user_id
         WHERE p.project_id = ?
        """, projectId);
    return row == null ? "" : text(row.get("login_id"));
  }

  private String emailOfDatabase(Connection connection, UUID databaseId) throws SQLException {
    Map<String, Object> row = one(connection, """
        SELECT u.login_id
          FROM catalog.intake_database d
          JOIN catalog.intake_project p ON p.project_id = d.project_id
          JOIN catalog.intake_user u ON u.user_id = p.user_id
         WHERE d.database_id = ?
        """, databaseId);
    return row == null ? "" : text(row.get("login_id"));
  }

  private String emailOfSchema(Connection connection, UUID schemaId) throws SQLException {
    Map<String, Object> row = one(connection, """
        SELECT u.login_id
          FROM catalog.intake_schema s
          JOIN catalog.intake_database d ON d.database_id = s.database_id
          JOIN catalog.intake_project p ON p.project_id = d.project_id
          JOIN catalog.intake_user u ON u.user_id = p.user_id
         WHERE s.schema_id = ?
        """, schemaId);
    return row == null ? "" : text(row.get("login_id"));
  }

  private String emailOfTable(Connection connection, UUID tableId) throws SQLException {
    Map<String, Object> row = one(connection, """
        SELECT u.login_id
          FROM catalog.intake_table t
          JOIN catalog.intake_schema s ON s.schema_id = t.schema_id
          JOIN catalog.intake_database d ON d.database_id = s.database_id
          JOIN catalog.intake_project p ON p.project_id = d.project_id
          JOIN catalog.intake_user u ON u.user_id = p.user_id
         WHERE t.table_id = ?
        """, tableId);
    return row == null ? "" : text(row.get("login_id"));
  }

  private static String requestType(Map<String, Object> body) {
    String value = text(body.get("requestType")).toUpperCase(Locale.ROOT);
    if (value.isBlank()) {
      return "NEW";
    }
    if (!value.equals("NEW") && !value.equals("MODIFY")) {
      throw new BadRequestException("Request type must be NEW or MODIFY");
    }
    return value;
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
  }

  private static String required(Map<String, Object> body, String key) {
    String value = text(body.get(key));
    if (value.isBlank()) {
      throw new BadRequestException(key + " is required");
    }
    return value;
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static UUID uuid(String value, String label) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException(label + " id is not valid");
    }
  }

  private static String quote(String name) {
    return "\"" + name + "\"";
  }

  private static RuntimeException sql(SQLException exception) {
    String message = exception.getMessage() == null ? "Database request failed" : exception.getMessage();
    return new BadRequestException(message);
  }

  private record SchemaRef(UUID schemaId, String schemaName, String databaseName) {}

  private record TableRef(String tableName, String schemaName, String databaseName) {}
}
