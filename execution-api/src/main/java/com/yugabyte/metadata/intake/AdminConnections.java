package com.yugabyte.metadata.intake;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdminConnections {
  private final String url;
  private final String username;
  private final String password;

  public AdminConnections(
      @Value("${spring.datasource.url}") String url,
      @Value("${intake.admin.username}") String username,
      @Value("${intake.admin.password}") String password) {
    this.url = url;
    this.username = username;
    this.password = password;
  }

  public Connection openCatalog() throws SQLException {
    return open(databaseName(url));
  }

  public Connection open(String database) throws SQLException {
    Connection connection = DriverManager.getConnection(jdbcUrl(database), username, password);
    connection.setAutoCommit(true);
    return connection;
  }

  public Connection open(String host, int port, String database, String login, String secret, String sslMode) throws SQLException {
    String ssl = sslMode == null || sslMode.isBlank() ? "disable" : sslMode;
    String target = "jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=" + ssl;
    Connection connection = DriverManager.getConnection(target, login, secret);
    connection.setAutoCommit(true);
    return connection;
  }

  private String jdbcUrl(String database) {
    int query = url.indexOf('?');
    String base = query < 0 ? url : url.substring(0, query);
    String suffix = query < 0 ? "" : url.substring(query);
    int slash = base.lastIndexOf('/');
    return base.substring(0, slash + 1) + database + suffix;
  }

  private static String databaseName(String jdbcUrl) {
    int query = jdbcUrl.indexOf('?');
    String base = query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
    return base.substring(base.lastIndexOf('/') + 1);
  }
}
