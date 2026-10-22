package com.yugabyte.metadata.intake;

import com.yugabyte.metadata.web.BadRequestException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Writes the ODP360 intake form into catalog.data_owner, catalog.table_context,
 * and the database, schema, table, column, and index rows those records belong to.
 */
public final class CatalogRegistrar {
  private CatalogRegistrar() {}


  private static String upsertOwner(Connection connection, ProjectContext project, String actor) throws SQLException {
    String email = emailOrNull(project.techOwnerEmail());
    String identity = email != null ? email : project.platformUserId();
    if (identity == null || identity.isBlank()) {
      throw new BadRequestException("Catalog registration needs a user id");
    }
    String ownerId = ownerId(identity);
    String primaryName = email != null ? contactName(email) : project.platformUserId();
    String secondaryEmail = emailOrNull(project.developerEmail());
    String secondaryName = secondaryEmail == null ? null : contactName(secondaryEmail);
    if (secondaryEmail != null && secondaryEmail.equalsIgnoreCase(email == null ? "" : email)) {
      secondaryEmail = null;
      secondaryName = null;
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO catalog.data_owner (
            data_owner_id, owner_name, primary_contact_name, primary_contact_email,
            secondary_contact_name, secondary_contact_email, platform_user_id, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (data_owner_id) DO UPDATE SET
            owner_name = EXCLUDED.owner_name,
            primary_contact_name = EXCLUDED.primary_contact_name,
            primary_contact_email = EXCLUDED.primary_contact_email,
            secondary_contact_name = EXCLUDED.secondary_contact_name,
            secondary_contact_email = EXCLUDED.secondary_contact_email,
            platform_user_id = COALESCE(EXCLUDED.platform_user_id, catalog.data_owner.platform_user_id),
            updated_at = CURRENT_TIMESTAMP,
            updated_by = EXCLUDED.updated_by
        """)) {
      statement.setString(1, ownerId);
      statement.setString(2, limit(project.projectName(), 255));
      statement.setString(3, limit(primaryName, 255));
      statement.setString(4, email);
      statement.setString(5, secondaryName);
      statement.setString(6, secondaryEmail);
      statement.setString(7, blankToNull(project.platformUserId()));
      statement.setString(8, actor);
      statement.setString(9, actor);
      statement.executeUpdate();
    }
    return ownerId;
  }

  private static UUID insertContext(
      Connection connection,
      ProjectContext project,
      String systemOfOrigin,
      Long estimatedBytes,
      Integer estimatedTps,
      String actor)
      throws SQLException {
    String productLine = firstText(project.domain(), project.subDomain());
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO catalog.table_context (
            car_id, business_line, product_line, system_of_origin_code,
            estimated_storage_volume, estimated_tps, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING table_context_id
        """)) {
      statement.setString(1, blankToNull(project.carId()));
      statement.setString(2, blankToNull(project.businessLine()));
      statement.setString(3, productLine);
      statement.setString(4, blankToNull(systemOfOrigin));
      if (estimatedBytes == null) {
        statement.setObject(5, null);
      } else {
        statement.setLong(5, estimatedBytes);
      }
      if (estimatedTps == null) {
        statement.setObject(6, null);
      } else {
        statement.setInt(6, estimatedTps);
      }
      statement.setString(7, actor);
      statement.setString(8, actor);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getObject(1, UUID.class);
      }
    }
  }


  public static Registration registerUploaded(
      Connection connection,
      String databaseName,
      String schemaName,
      String tableName,
      List<DdlBuilder.ParsedColumn> columns,
      List<String> partitionKey,
      String ddl,
      ProjectContext project,
      String cloud,
      String environment)
      throws SQLException {
    String actor = actor(firstText(project.platformUserId(), project.techOwnerEmail()));
    String ownerId = upsertOwner(connection, project, actor);
    UUID contextId = insertContext(connection, project, null, null, null, actor);
    UUID universeId = universeFor(connection, cloud, environment);
    UUID databaseId = upsertDatabase(connection, universeId, databaseName, project.techOwnerEmail(), actor);
    UUID schemaId = upsertSchema(connection, databaseId, schemaName, project.techOwnerEmail(), actor);
    boolean partitioned = partitionKey != null && !partitionKey.isEmpty();
    String partition = partitioned ? "HASH (" + String.join(", ", partitionKey) + ")" : null;
    String properties = "{\"classification\":\"INTERNAL\",\"business_domain\":\"" + domainCode(project.businessLine()) + "\"}";
    UUID tableId;
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO catalog.table_metadata (
            schema_id, data_owner_id, table_context_id, table_name, is_partitioned,
            partition_strategy, partition_definition, definition_sql, properties, source_system, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'intake', ?, ?)
        ON CONFLICT (schema_id, table_name) DO UPDATE SET
            data_owner_id = EXCLUDED.data_owner_id,
            table_context_id = EXCLUDED.table_context_id,
            is_partitioned = EXCLUDED.is_partitioned,
            partition_strategy = EXCLUDED.partition_strategy,
            partition_definition = EXCLUDED.partition_definition,
            definition_sql = EXCLUDED.definition_sql,
            properties = EXCLUDED.properties,
            updated_at = CURRENT_TIMESTAMP,
            updated_by = EXCLUDED.updated_by
        RETURNING table_id
        """)) {
      statement.setObject(1, schemaId);
      statement.setString(2, ownerId);
      statement.setObject(3, contextId);
      statement.setString(4, tableName);
      statement.setBoolean(5, partitioned);
      statement.setString(6, partitioned ? "HASH" : null);
      statement.setString(7, partition);
      statement.setString(8, ddl);
      statement.setString(9, properties);
      statement.setString(10, actor);
      statement.setString(11, actor);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        tableId = rows.getObject(1, UUID.class);
      }
    }
    replaceUploadedColumns(connection, tableId, columns, actor);
    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM catalog.index_metadata WHERE table_id = ?")) {
      delete.setObject(1, tableId);
      delete.executeUpdate();
    }
    List<String> primaryColumns = new ArrayList<>();
    for (DdlBuilder.ParsedColumn column : columns) {
      if (column.primaryKey()) {
        primaryColumns.add(column.name());
      }
    }
    if (!primaryColumns.isEmpty()) {
      UUID primaryId = insertIndex(connection, tableId, tableName + "_pkey", "PRIMARY_KEY", true, true,
          partitioned ? partition : "PRIMARY KEY (" + String.join(", ", primaryColumns) + ")", actor);
      int position = 1;
      for (String column : primaryColumns) {
        insertIndexColumn(connection, primaryId, position++, column, "ASC", actor);
      }
    }
    return new Registration(ownerId, contextId, tableId);
  }

  public static void addUploadedIndex(Connection connection, UUID catalogTableId, DdlBuilder.ParsedIndex index, String actor)
      throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement(
        "DELETE FROM catalog.index_metadata WHERE table_id = ? AND index_name = ?")) {
      delete.setObject(1, catalogTableId);
      delete.setString(2, index.indexName());
      delete.executeUpdate();
    }
    String kind = index.unique() ? "UNIQUE" : "NON_UNIQUE";
    UUID indexId = insertIndex(connection, catalogTableId, index.indexName(), kind, index.unique(), false,
        "INDEX (" + String.join(", ", index.columns()) + ")", actor);
    int position = 1;
    for (String column : index.columns()) {
      insertIndexColumn(connection, indexId, position++, column, "ASC", actor);
    }
  }

  public static void addUploadedColumn(
      Connection connection, UUID catalogTableId, String name, String type, boolean nullable, String actor)
      throws SQLException {
    Integer existing = null;
    try (PreparedStatement lookup = connection.prepareStatement(
        "SELECT ordinal_position FROM catalog.column_metadata WHERE table_id = ? AND column_name = ?")) {
      lookup.setObject(1, catalogTableId);
      lookup.setString(2, name);
      try (ResultSet rows = lookup.executeQuery()) {
        if (rows.next()) {
          existing = rows.getInt(1);
        }
      }
    }
    if (existing != null) {
      try (PreparedStatement update = connection.prepareStatement("""
          UPDATE catalog.column_metadata
             SET data_type = ?, is_nullable = ?, updated_at = CURRENT_TIMESTAMP, updated_by = ?
           WHERE table_id = ? AND column_name = ?
          """)) {
        update.setString(1, type);
        update.setBoolean(2, nullable);
        update.setString(3, actor);
        update.setObject(4, catalogTableId);
        update.setString(5, name);
        update.executeUpdate();
      }
      return;
    }
    int position = 1;
    try (PreparedStatement next = connection.prepareStatement(
        "SELECT COALESCE(MAX(ordinal_position), 0) + 1 FROM catalog.column_metadata WHERE table_id = ?")) {
      next.setObject(1, catalogTableId);
      try (ResultSet rows = next.executeQuery()) {
        if (rows.next()) {
          position = rows.getInt(1);
        }
      }
    }
    try (PreparedStatement insert = connection.prepareStatement("""
        INSERT INTO catalog.column_metadata (
            table_id, column_name, ordinal_position, data_type, is_nullable, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """)) {
      insert.setObject(1, catalogTableId);
      insert.setString(2, name);
      insert.setInt(3, position);
      insert.setString(4, type);
      insert.setBoolean(5, nullable);
      insert.setString(6, actor);
      insert.setString(7, actor);
      insert.executeUpdate();
    }
  }

  public static UUID catalogTableId(
      Connection connection, String cloud, String environment, String databaseName, String schemaName, String tableName)
      throws SQLException {
    UUID universe = universeFor(connection, cloud, environment);
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT t.table_id
          FROM catalog.table_metadata t
          JOIN catalog.schema_metadata s ON s.schema_id = t.schema_id
          JOIN catalog.database_metadata d ON d.database_id = s.database_id
         WHERE d.universe_id = ? AND d.database_name = ? AND s.schema_name = ? AND t.table_name = ?
        """)) {
      statement.setObject(1, universe);
      statement.setString(2, databaseName);
      statement.setString(3, schemaName);
      statement.setString(4, tableName);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getObject(1, UUID.class) : null;
      }
    }
  }

  private static void replaceUploadedColumns(
      Connection connection, UUID tableId, List<DdlBuilder.ParsedColumn> columns, String actor) throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM catalog.column_metadata WHERE table_id = ?")) {
      delete.setObject(1, tableId);
      delete.executeUpdate();
    }
    int position = 1;
    for (DdlBuilder.ParsedColumn column : columns) {
      try (PreparedStatement insert = connection.prepareStatement("""
          INSERT INTO catalog.column_metadata (
              table_id, column_name, ordinal_position, data_type, is_nullable, created_by, updated_by)
          VALUES (?, ?, ?, ?, ?, ?, ?)
          """)) {
        insert.setObject(1, tableId);
        insert.setString(2, column.name());
        insert.setInt(3, position++);
        insert.setString(4, column.type());
        insert.setBoolean(5, column.nullable());
        insert.setString(6, actor);
        insert.setString(7, actor);
        insert.executeUpdate();
      }
    }
  }


  private static UUID insertIndex(
      Connection connection,
      UUID tableId,
      String name,
      String kind,
      boolean unique,
      boolean primary,
      String definition,
      String actor)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO catalog.index_metadata (
            table_id, index_name, index_kind, is_unique, is_primary, index_definition, created_by, updated_by)
        VALUES (?, ?, CAST(? AS catalog.index_kind), ?, ?, ?, ?, ?)
        RETURNING index_id
        """)) {
      statement.setObject(1, tableId);
      statement.setString(2, name);
      statement.setString(3, kind);
      statement.setBoolean(4, unique);
      statement.setBoolean(5, primary);
      statement.setString(6, definition);
      statement.setString(7, actor);
      statement.setString(8, actor);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private static void insertIndexColumn(
      Connection connection, UUID indexId, int position, String column, String direction, String actor)
      throws SQLException {
    String sort = "DESC".equalsIgnoreCase(direction) ? "DESC" : "ASC";
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO catalog.index_column_metadata (
            index_id, ordinal_position, column_name, sort_direction, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?)
        """)) {
      statement.setObject(1, indexId);
      statement.setInt(2, position);
      statement.setString(3, column);
      statement.setString(4, sort);
      statement.setString(5, actor);
      statement.setString(6, actor);
      statement.executeUpdate();
    }
  }

  private static UUID upsertDatabase(Connection connection, UUID universeId, String databaseName, String owner, String actor)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO catalog.database_metadata (
            universe_id, database_name, database_owner, definition_sql, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (universe_id, database_name) DO UPDATE SET
            updated_at = CURRENT_TIMESTAMP,
            updated_by = EXCLUDED.updated_by
        RETURNING database_id
        """)) {
      statement.setObject(1, universeId);
      statement.setString(2, databaseName);
      statement.setString(3, owner);
      statement.setString(4, "CREATE DATABASE " + databaseName);
      statement.setString(5, actor);
      statement.setString(6, actor);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private static UUID upsertSchema(Connection connection, UUID databaseId, String schemaName, String owner, String actor)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO catalog.schema_metadata (
            database_id, schema_name, schema_owner, definition_sql, created_by, updated_by)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (database_id, schema_name) DO UPDATE SET
            updated_at = CURRENT_TIMESTAMP,
            updated_by = EXCLUDED.updated_by
        RETURNING schema_id
        """)) {
      statement.setObject(1, databaseId);
      statement.setString(2, schemaName);
      statement.setString(3, owner);
      statement.setString(4, "CREATE SCHEMA " + schemaName);
      statement.setString(5, actor);
      statement.setString(6, actor);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private static UUID universeFor(Connection connection, String cloud, String environment) throws SQLException {
    String provider = cloud == null || cloud.isBlank() ? "AWS" : cloud.trim().toUpperCase(Locale.ROOT);
    String target = environment == null || environment.isBlank() ? "DEV" : environment.trim().toUpperCase(Locale.ROOT);
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT u.universe_id
          FROM catalog.universe u
          JOIN catalog.environment e ON e.environment_id = u.environment_id
          JOIN catalog.cloud c ON c.cloud_id = e.cloud_id
         WHERE c.cloud_code::text = ? AND e.environment_code::text = ?
         ORDER BY u.universe_code
         LIMIT 1
        """)) {
      statement.setString(1, provider);
      statement.setString(2, target);
      try (ResultSet rows = statement.executeQuery()) {
        if (rows.next()) {
          return rows.getObject(1, UUID.class);
        }
      }
    }
    throw new BadRequestException("No " + provider + " " + target + " universe is available to register the table");
  }

  private static UUID oneUuid(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getObject(1, UUID.class) : null;
    }
  }

  static Long estimatedBytes(String volumeGb, Long totalRecords, Long recordSize) {
    if (volumeGb != null && !volumeGb.isBlank()) {
      return new BigDecimal(volumeGb.trim()).multiply(BigDecimal.valueOf(1024L * 1024L * 1024L)).longValue();
    }
    if (totalRecords != null && recordSize != null) {
      return Math.multiplyExact(totalRecords, recordSize);
    }
    return null;
  }

  static Long longOrNull(String value, String label) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      long parsed = new BigDecimal(value.trim()).longValue();
      if (parsed < 0) {
        throw new BadRequestException(label + " cannot be negative");
      }
      return parsed;
    } catch (NumberFormatException | ArithmeticException exception) {
      throw new BadRequestException(label + " must be a number");
    }
  }

  static Integer tps(String average, String peak) {
    Integer averageTps = integerOrNull(average, "Average TPS");
    Integer peakTps = integerOrNull(peak, "Peak TPS");
    if (peakTps != null) {
      return peakTps;
    }
    return averageTps;
  }

  private static Integer integerOrNull(String value, String label) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      long parsed = new BigDecimal(value.trim()).longValue();
      if (parsed < 0 || parsed > Integer.MAX_VALUE) {
        throw new BadRequestException(label + " is out of range");
      }
      return (int) parsed;
    } catch (NumberFormatException | ArithmeticException exception) {
      throw new BadRequestException(label + " must be a number");
    }
  }

  private static String ownerId(String email) {
    String compact = email.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    if (compact.length() > 90) {
      compact = compact.substring(0, 90);
    }
    return "owner-" + compact;
  }

  private static String contactName(String email) {
    int at = email.indexOf('@');
    String local = at > 0 ? email.substring(0, at) : email;
    String spaced = local.replace('.', ' ').replace('_', ' ').trim();
    return spaced.isEmpty() ? "Owner" : spaced;
  }

  private static String emailOrNull(String value) {
    String email = blankToNull(value);
    return email != null && email.contains("@") ? email : null;
  }

  private static String actor(String identity) {
    if (identity == null || identity.isBlank()) {
      return "catalog";
    }
    return identity.length() <= 100 ? identity : identity.substring(0, 100);
  }

  private static String domainCode(String businessLine) {
    if (businessLine == null || businessLine.isBlank()) {
      return "GENERAL";
    }
    String code = businessLine.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    return code.isBlank() ? "GENERAL" : code;
  }

  private static String firstText(String first, String second) {
    if (first != null && !first.isBlank()) {
      return first.trim();
    }
    return blankToNull(second);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String limit(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  public record ProjectContext(
      String projectName,
      String businessLine,
      String domain,
      String subDomain,
      String edaiReqId,
      String techOwnerEmail,
      String developerEmail,
      String platformUserId,
      String carId) {}

  public record Registration(String dataOwnerId, UUID tableContextId, UUID catalogTableId) {}
}
