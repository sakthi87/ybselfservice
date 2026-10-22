package com.yugabyte.metadata.execution;

import com.yugabyte.metadata.intake.AdminConnections;
import com.yugabyte.metadata.web.BadRequestException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UniverseConnections {
  private final AdminConnections connections;
  private final String localPassword;

  public UniverseConnections(
      AdminConnections connections,
      @Value("${intake.admin.password}") String localPassword) {
    this.connections = connections;
    this.localPassword = localPassword;
  }

  public UniverseTarget forCode(String universeCode) {
    try (Connection connection = connections.openCatalog()) {
      try (PreparedStatement statement = connection.prepareStatement("""
          SELECT u.universe_code, c.cloud_code::text AS cloud_code, e.environment_code::text AS environment_code,
                 uc.ysql_host, uc.ysql_port, uc.admin_database, uc.auth_mode,
                 uc.username, uc.secret_ref, uc.ssl_mode
            FROM catalog.universe_connection uc
            JOIN catalog.universe u ON u.universe_id = uc.universe_id
            JOIN catalog.environment e ON e.environment_id = u.environment_id
            JOIN catalog.cloud c ON c.cloud_id = e.cloud_id
           WHERE u.universe_code = ?
             AND uc.status = 'ACTIVE'
          """)) {
        statement.setString(1, universeCode);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            throw new BadRequestException("No active connection is configured for universe " + universeCode);
          }
          return read(rows);
        }
      }
    } catch (SQLException exception) {
      throw new BadRequestException(exception.getMessage() == null ? "Could not read the universe connection" : exception.getMessage());
    }
  }

  public UniverseTarget dev() {
    return forCode("AWS-DEV-YB01");
  }

  private UniverseTarget read(ResultSet rows) throws SQLException {
    String authMode = rows.getString("auth_mode");
    if (!"PASSWORD".equals(authMode)) {
      throw new BadRequestException("Authentication mode " + authMode + " is not enabled yet");
    }
    String secretRef = rows.getString("secret_ref");
    String password = secretRef == null || secretRef.isBlank() ? "" : System.getenv(secretRef);
    if (password == null || password.isBlank()) {
      password = localPassword;
    }
    return new UniverseTarget(
        rows.getString("universe_code"),
        rows.getString("cloud_code"),
        rows.getString("environment_code"),
        rows.getString("ysql_host"),
        rows.getInt("ysql_port"),
        rows.getString("admin_database"),
        rows.getString("username"),
        password,
        rows.getString("ssl_mode"));
  }

  public record UniverseTarget(
      String universeCode,
      String cloud,
      String environment,
      String host,
      int port,
      String adminDatabase,
      String username,
      String password,
      String sslMode) {
  }
}
