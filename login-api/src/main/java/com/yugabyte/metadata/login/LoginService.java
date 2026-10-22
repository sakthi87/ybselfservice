package com.yugabyte.metadata.login;

import com.yugabyte.metadata.intake.AdminConnections;
import com.yugabyte.metadata.web.BadRequestException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
  private static final Pattern USER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{1,62}[a-z0-9]");
  private static final PasswordEncoder PASSWORDS = new BCryptPasswordEncoder();

  private final AdminConnections connections;

  public LoginService(AdminConnections connections) {
    this.connections = connections;
  }

  public Map<String, Object> session(Map<String, Object> body) {
    String userId = userId(body.get("userId"));
    String password = password(body.get("password"));
    try (Connection connection = connections.openCatalog()) {
      User user = find(connection, userId);
      if (user == null) {
        UUID id = insert(connection, userId, PASSWORDS.encode(password));
        audit(connection, userId);
        return session(id, userId);
      }
      if (user.passwordHash == null || user.passwordHash.isBlank()) {
        storePassword(connection, user.id, PASSWORDS.encode(password));
      } else if (!PASSWORDS.matches(password, user.passwordHash)) {
        throw new BadRequestException("User id or password is not valid");
      }
      audit(connection, userId);
      return session(user.id, userId);
    } catch (SQLException exception) {
      throw new BadRequestException("Sign-in could not be completed");
    }
  }

  public Map<String, Object> ensure(Map<String, Object> body) {
    String userId = userId(body.get("userId"));
    try (Connection connection = connections.openCatalog()) {
      User user = find(connection, userId);
      if (user == null) {
        throw new BadRequestException("Sign in before filing an intake");
      }
      return session(user.id, user.loginId);
    } catch (SQLException exception) {
      throw new BadRequestException("Sign-in could not be completed");
    }
  }

  private static Map<String, Object> session(UUID id, String userId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id.toString());
    result.put("userId", userId);
    result.put("subjectId", "user:" + userId);
    return result;
  }

  private static UUID insert(Connection connection, String userId, String passwordHash) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement("""
        INSERT INTO catalog.intake_user (full_name, email, login_id, password_hash, subject_id)
        VALUES (?, NULL, ?, ?, ?) RETURNING user_id
        """)) {
      insert.setString(1, userId);
      insert.setString(2, userId);
      insert.setString(3, passwordHash);
      insert.setString(4, "user:" + userId);
      try (ResultSet rows = insert.executeQuery()) {
        rows.next();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private static void storePassword(Connection connection, UUID id, String passwordHash) throws SQLException {
    try (PreparedStatement update = connection.prepareStatement("""
        UPDATE catalog.intake_user SET password_hash = ? WHERE user_id = ?
        """)) {
      update.setString(1, passwordHash);
      update.setObject(2, id);
      update.executeUpdate();
    }
  }

  private static void audit(Connection connection, String userId) throws SQLException {
    try (PreparedStatement activity = connection.prepareStatement("""
        INSERT INTO catalog.platform_activity
            (user_email, action, object_type, object_name, detail, request_id, description)
        VALUES (?, 'SIGN_IN', 'user', ?, 'Signed in', NULL, NULL)
        """)) {
      activity.setString(1, userId);
      activity.setString(2, userId);
      activity.executeUpdate();
    }
  }

  private static User find(Connection connection, String userId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT user_id, login_id, password_hash
          FROM catalog.intake_user
         WHERE lower(login_id) = ?
        """)) {
      statement.setString(1, userId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        return new User(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3));
      }
    }
  }

  private static String userId(Object value) {
    String userId = value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    if (!USER_ID.matcher(userId).matches()) {
      throw new BadRequestException("User id must be 3 to 64 characters and use letters, numbers, dots, underscores, or hyphens");
    }
    return userId;
  }

  private static String password(Object value) {
    String password = value == null ? "" : String.valueOf(value);
    if (password.length() < 8 || password.length() > 72) {
      throw new BadRequestException("Password must be 8 to 72 characters");
    }
    return password;
  }

  private record User(UUID id, String loginId, String passwordHash) {}
}
