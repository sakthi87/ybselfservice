package com.yugabyte.metadata.intake;

import com.yugabyte.metadata.web.BadRequestException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DdlBuilder {
  private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");
  private static final Pattern TYPE = Pattern.compile(
      "^(uuid|text|integer|int|bigint|smallint|boolean|date|timestamptz|timestamp|jsonb|real|double precision|varchar\\(\\d{1,4}\\)|numeric(\\(\\d{1,2}(,\\s*\\d{1,2})?\\))?)$",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern FORBIDDEN = Pattern.compile(
      "\\b(drop|truncate|delete|insert|update|grant|revoke|copy|vacuum|create\\s+database|create\\s+role|create\\s+user|alter\\s+role|alter\\s+system)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern ALLOWED = Pattern.compile(
      "^(create\\s+schema\\s+(if\\s+not\\s+exists\\s+)?[A-Za-z_][A-Za-z0-9_]*"
          + "|create\\s+table\\s+(if\\s+not\\s+exists\\s+)?([A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)?[A-Za-z_][A-Za-z0-9_]*\\s*\\(.*"
          + "|create\\s+(unique\\s+)?index\\s+(if\\s+not\\s+exists\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s+on\\s+.*"
          + "|alter\\s+table\\s+([A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)?[A-Za-z_][A-Za-z0-9_]*\\s+add\\s+column\\s+.*)",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Set<String> RESERVED_DATABASES = Set.of(
      "yugabyte", "postgres", "template0", "template1", "yugabyte_metadata", "system_platform");

  public String requireName(String value, String label) {
    if (value == null || !NAME.matcher(value.trim()).matches()) {
      throw new BadRequestException(label + " must be a letter or underscore followed by letters, digits, or underscores");
    }
    return value.trim();
  }

  public void assertExecutable(String statement) {
    String compact = statement.replaceAll("\\s+", " ").trim();
    if (FORBIDDEN.matcher(compact).find()) {
      throw new BadRequestException("Only CREATE SCHEMA, CREATE TABLE, CREATE INDEX, ALTER TABLE ADD COLUMN, and COMMENT are allowed");
    }
  }

  public void rejectPublicSchema(String schemaName) {
    if ("public".equalsIgnoreCase(schemaName)) {
      throw new BadRequestException("The public schema is not allowed. Create a named schema.");
    }
  }

  public void requireDatabaseName(String databaseName) {
    String name = requireName(databaseName, "Database name");
    if (RESERVED_DATABASES.contains(name.toLowerCase(Locale.ROOT))) {
      throw new BadRequestException("Database name " + name + " is reserved");
    }
  }

  public String tableDdl(String schema, String table, String description, List<Map<String, Object>> columns, List<Map<String, Object>> indexes) {
    if (columns == null || columns.isEmpty()) {
      throw new BadRequestException("Add at least one column");
    }
    List<String> primary = new ArrayList<>();
    StringBuilder ddl = new StringBuilder();
    ddl.append("CREATE TABLE IF NOT EXISTS ").append(quote(schema)).append('.').append(quote(table)).append(" (\n");
    for (int i = 0; i < columns.size(); i++) {
      Map<String, Object> column = columns.get(i);
      String name = requireName(text(column.get("name")), "Column name");
      String type = requireType(text(column.get("type")));
      boolean nullable = column.get("nullable") == null || Boolean.TRUE.equals(column.get("nullable"));
      boolean primaryKey = Boolean.TRUE.equals(column.get("primaryKey"));
      if (primaryKey) {
        primary.add(name);
        nullable = false;
      }
      ddl.append("    ").append(quote(name)).append(' ').append(type);
      ddl.append(nullable ? " NULL" : " NOT NULL");
      String defaultExpression = text(column.get("defaultExpression"));
      if (!defaultExpression.isBlank()) {
        ddl.append(" DEFAULT ").append(defaultExpression.trim());
      }
      if (i < columns.size() - 1 || !primary.isEmpty()) {
        ddl.append(',');
      }
      ddl.append('\n');
    }
    if (primary.isEmpty()) {
      throw new BadRequestException("Choose at least one primary key column");
    }
    ddl.append("    PRIMARY KEY (");
    ddl.append(String.join(", ", primary.stream().map(this::quote).toList()));
    ddl.append(")\n)");
    if (description != null && !description.isBlank()) {
      ddl.append("\n");
    }
    ddl.append(';');
    if (indexes != null) {
      for (Map<String, Object> index : indexes) {
        ddl.append("\n\n").append(indexDdl(schema, table, index));
      }
    }
    return ddl.toString();
  }

  public String addColumnDdl(String schema, String table, Map<String, Object> column) {
    String name = requireName(text(column.get("name")), "Column name");
    String type = requireType(text(column.get("type")));
    boolean nullable = column.get("nullable") == null || Boolean.TRUE.equals(column.get("nullable"));
    StringBuilder ddl = new StringBuilder();
    ddl.append("ALTER TABLE ").append(quote(schema)).append('.').append(quote(table));
    ddl.append(" ADD COLUMN ").append(quote(name)).append(' ').append(type);
    ddl.append(nullable ? " NULL" : " NOT NULL");
    String defaultExpression = text(column.get("defaultExpression"));
    if (!defaultExpression.isBlank()) {
      ddl.append(" DEFAULT ").append(defaultExpression.trim());
    }
    ddl.append(';');
    return ddl.toString();
  }

  public String indexDdl(String schema, String table, Map<String, Object> index) {
    String name = requireName(text(index.get("name")), "Index name");
    List<String> columns = columnList(index.get("columns"));
    if (columns.isEmpty()) {
      throw new BadRequestException("An index needs at least one column");
    }
    boolean unique = Boolean.TRUE.equals(index.get("unique"));
    StringBuilder ddl = new StringBuilder();
    ddl.append("CREATE ").append(unique ? "UNIQUE " : "").append("INDEX IF NOT EXISTS ").append(quote(name));
    ddl.append(" ON ").append(quote(schema)).append('.').append(quote(table)).append(" (");
    ddl.append(String.join(", ", columns.stream().map(this::quote).toList()));
    ddl.append(");");
    return ddl.toString();
  }

  public List<Map<String, Object>> previewScript(String sql, String schema) {
    List<String> statements = statements(sql);
    if (statements.isEmpty()) {
      throw new BadRequestException("The DDL script is empty");
    }
    List<Map<String, Object>> preview = new ArrayList<>();
    for (String statement : statements) {
      validateStatement(statement, schema);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("kind", kind(statement));
      item.put("name", objectName(statement));
      item.put("sql", statement);
      preview.add(item);
    }
    return preview;
  }

  public List<String> statements(String sql) {
    if (sql == null) {
      return List.of();
    }
    String stripped = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean quote = false;
    for (int i = 0; i < stripped.length(); i++) {
      char character = stripped.charAt(i);
      if (character == '\'') {
        quote = !quote;
        current.append(character);
      } else if (character == ';' && !quote) {
        addStatement(statements, current);
        current.setLength(0);
      } else {
        current.append(character);
      }
    }
    addStatement(statements, current);
    return statements;
  }

  public String targetDatabase(String databaseName, String environment) {
    return switch (environment) {
      case "IT" -> databaseName + "_it";
      case "UAT" -> databaseName + "_uat";
      case "PROD" -> databaseName + "_prod";
      default -> throw new BadRequestException("Deploy to IT, UAT, or PROD");
    };
  }

  private void validateStatement(String statement, String schema) {
    String compact = statement.replaceAll("\\s+", " ").trim();
    if (FORBIDDEN.matcher(compact).find() || !ALLOWED.matcher(compact).matches()) {
      throw new BadRequestException("Only CREATE SCHEMA, CREATE TABLE, CREATE INDEX, and ALTER TABLE ADD COLUMN are allowed");
    }
    if (compact.toLowerCase(Locale.ROOT).contains("public.")
        || compact.toLowerCase(Locale.ROOT).matches(".*create\\s+schema\\s+(if\\s+not\\s+exists\\s+)?public\\b.*")) {
      throw new BadRequestException("The public schema is not allowed");
    }
    for (String qualifier : qualifiers(compact)) {
      if (!qualifier.equalsIgnoreCase(schema)) {
        throw new BadRequestException("Qualify objects with schema " + schema + " only");
      }
    }
  }

  private static List<String> qualifiers(String sql) {
    List<String> names = new ArrayList<>();
    var matcher = Pattern.compile("(?i)\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*[A-Za-z_]").matcher(sql);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }

  private static String kind(String statement) {
    String lower = statement.toLowerCase(Locale.ROOT);
    if (lower.startsWith("create schema")) {
      return "SCHEMA";
    }
    if (lower.startsWith("create table")) {
      return "TABLE";
    }
    if (lower.startsWith("create unique index") || lower.startsWith("create index")) {
      return "INDEX";
    }
    return "COLUMN";
  }

  private static String objectName(String statement) {
    var matcher = Pattern.compile("(?i)(?:table|schema|index)\\s+(?:if\\s+not\\s+exists\\s+)?(?:[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)?([A-Za-z_][A-Za-z0-9_]*)").matcher(statement);
    return matcher.find() ? matcher.group(1) : "";
  }

  private String requireType(String type) {
    if (type == null || !TYPE.matcher(type.trim()).matches()) {
      throw new BadRequestException("Unsupported column type " + type);
    }
    return type.trim();
  }

  private List<String> columnList(Object value) {
    List<String> names = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (Object item : list) {
        names.add(requireName(String.valueOf(item), "Index column"));
      }
    } else if (value != null) {
      for (String part : String.valueOf(value).split(",")) {
        if (!part.isBlank()) {
          names.add(requireName(part, "Index column"));
        }
      }
    }
    return names;
  }

  public ParsedTable parseCreateTable(String sql) {
    String body = tableBody(sql);
    List<String> partitionKey = new ArrayList<>();
    List<String> clusteringKey = new ArrayList<>();
    List<ParsedColumn> columns = new ArrayList<>();
    for (String part : splitTopLevel(body)) {
      if (part.matches("(?is)^(constraint\\s+\\S+\\s+)?primary\\s+key\\b.*")) {
        readPrimaryKey(part, partitionKey, clusteringKey);
        continue;
      }
      ParsedColumn column = readColumn(part);
      if (column != null) {
        columns.add(column);
      }
    }
    if (columns.isEmpty()) {
      throw new BadRequestException("CREATE TABLE needs at least one column");
    }
    Set<String> primary = new HashSet<>();
    partitionKey.forEach(name -> primary.add(name.toLowerCase(Locale.ROOT)));
    clusteringKey.forEach(name -> primary.add(name.toLowerCase(Locale.ROOT)));
    List<ParsedColumn> marked = new ArrayList<>();
    for (ParsedColumn column : columns) {
      boolean key = column.primaryKey() || primary.contains(column.name().toLowerCase(Locale.ROOT));
      if (key && !partitionKey.stream().anyMatch(name -> name.equalsIgnoreCase(column.name()))
          && !clusteringKey.stream().anyMatch(name -> name.equalsIgnoreCase(column.name()))) {
        partitionKey.add(column.name());
      }
      marked.add(new ParsedColumn(column.name(), column.type(), key ? false : column.nullable(), key));
    }
    for (String key : partitionKey) {
      if (marked.stream().noneMatch(column -> column.name().equalsIgnoreCase(key))) {
        throw new BadRequestException("Primary key column " + key + " is not in the table");
      }
    }
    String tableName = identifier(objectName(sql));
    if (tableName.isBlank()) {
      throw new BadRequestException("CREATE TABLE needs a table name");
    }
    return new ParsedTable(tableName, marked, partitionKey, clusteringKey);
  }

  public ParsedIndex parseCreateIndex(String sql) {
    String compact = sql.replaceAll("\\s+", " ").trim().replaceAll(";$", "");
    var matcher = Pattern.compile(
        "(?i)^create\\s+(unique\\s+)?index\\s+(?:if\\s+not\\s+exists\\s+)?(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\s+on\\s+(?:\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_]*)?\\s*\\.?\\s*(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\s*\\((.+)\\)$")
        .matcher(compact);
    if (!matcher.matches()) {
      throw new BadRequestException("Could not read the CREATE INDEX statement");
    }
    String indexName = identifier(matcher.group(2) != null ? "\"" + matcher.group(2) + "\"" : matcher.group(3));
    String tableName = identifier(matcher.group(4) != null ? "\"" + matcher.group(4) + "\"" : matcher.group(5));
    List<String> columns = new ArrayList<>();
    for (String part : matcher.group(6).split(",")) {
      String name = part.trim().replaceAll("(?i)\\s+(asc|desc)$", "").trim();
      if (!name.isBlank()) {
        columns.add(identifier(name));
      }
    }
    if (columns.isEmpty()) {
      throw new BadRequestException("An index needs at least one column");
    }
    return new ParsedIndex(tableName, indexName, columns, matcher.group(1) != null);
  }

  public AddedColumn parseAddColumn(String sql) {
    String compact = sql.replaceAll("\\s+", " ").trim().replaceAll(";$", "");
    var matcher = Pattern.compile(
        "(?i)^alter\\s+table\\s+(?:(?:\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*)?(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\s+add\\s+column\\s+(?:if\\s+not\\s+exists\\s+)?(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\s+(.+)$")
        .matcher(compact);
    if (!matcher.matches()) {
      throw new BadRequestException("Could not read the ALTER TABLE statement");
    }
    String tableName = identifier(matcher.group(1) != null ? "\"" + matcher.group(1) + "\"" : matcher.group(2));
    String columnName = identifier(matcher.group(3) != null ? "\"" + matcher.group(3) + "\"" : matcher.group(4));
    String rest = matcher.group(5).trim();
    String type = columnType(rest);
    boolean nullable = !rest.matches("(?is).*\\bnot\\s+null\\b.*");
    return new AddedColumn(tableName, columnName, type, nullable);
  }

  private static String tableBody(String sql) {
    int open = sql.indexOf('(');
    if (open < 0) {
      throw new BadRequestException("CREATE TABLE needs a column list");
    }
    int depth = 0;
    boolean quote = false;
    for (int i = open; i < sql.length(); i++) {
      char character = sql.charAt(i);
      if (character == '\'') {
        quote = !quote;
      } else if (!quote && character == '(') {
        depth++;
      } else if (!quote && character == ')') {
        depth--;
        if (depth == 0) {
          return sql.substring(open + 1, i);
        }
      }
    }
    throw new BadRequestException("CREATE TABLE column list is not closed");
  }

  private static List<String> splitTopLevel(String body) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    boolean quote = false;
    for (int i = 0; i < body.length(); i++) {
      char character = body.charAt(i);
      if (character == '\'') {
        quote = !quote;
        current.append(character);
      } else if (!quote && character == '(') {
        depth++;
        current.append(character);
      } else if (!quote && character == ')') {
        depth--;
        current.append(character);
      } else if (!quote && depth == 0 && character == ',') {
        if (!current.toString().isBlank()) {
          parts.add(current.toString().trim());
        }
        current.setLength(0);
      } else {
        current.append(character);
      }
    }
    if (!current.toString().isBlank()) {
      parts.add(current.toString().trim());
    }
    return parts;
  }

  private static void readPrimaryKey(String part, List<String> partitionKey, List<String> clusteringKey) {
    String spec = part.replaceFirst("(?is)^(constraint\\s+\\S+\\s+)?primary\\s+key\\s*", "").trim();
    if (spec.startsWith("(") && spec.endsWith(")")) {
      spec = spec.substring(1, spec.length() - 1).trim();
    }
    var hash = Pattern.compile("(?is)^\\(([^)]*)\\)\\s*HASH\\s*(?:,\\s*(.*))?$").matcher(spec);
    if (hash.matches()) {
      addNames(partitionKey, hash.group(1));
      addNames(clusteringKey, hash.group(2));
      return;
    }
    addNames(partitionKey, spec);
  }

  private static void addNames(List<String> names, String list) {
    if (list == null || list.isBlank()) {
      return;
    }
    for (String part : splitTopLevel(list)) {
      String name = part.replaceAll("(?i)\\s+(asc|desc)$", "").trim();
      if (!name.isBlank()) {
        names.add(identifier(name));
      }
    }
  }

  private static ParsedColumn readColumn(String part) {
    var matcher = Pattern.compile("^(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(part.trim());
    if (!matcher.matches()) {
      return null;
    }
    String name = identifier(matcher.group(1) != null ? "\"" + matcher.group(1) + "\"" : matcher.group(2));
    String rest = matcher.group(3).trim();
    boolean primary = rest.matches("(?is).*\\bprimary\\s+key\\b.*");
    boolean nullable = !primary && !rest.matches("(?is).*\\bnot\\s+null\\b.*");
    return new ParsedColumn(name, columnType(rest), nullable, primary);
  }

  private static String columnType(String rest) {
    var cut = Pattern.compile("(?i)\\s+(not\\s+null|null|default|primary\\s+key|unique|references|constraint)\\b").matcher(rest);
    String type = (cut.find() ? rest.substring(0, cut.start()) : rest).trim();
    if (type.isBlank()) {
      throw new BadRequestException("A column is missing its data type");
    }
    return type;
  }

  private static String identifier(String token) {
    String trimmed = token.trim();
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  public record ParsedColumn(String name, String type, boolean nullable, boolean primaryKey) {}

  public record ParsedIndex(String tableName, String indexName, List<String> columns, boolean unique) {}

  public record ParsedTable(String name, List<ParsedColumn> columns, List<String> partitionKey, List<String> clusteringKey) {}

  public record AddedColumn(String tableName, String name, String type, boolean nullable) {}

  private static void addStatement(List<String> statements, StringBuilder current) {
    String statement = current.toString().trim();
    if (!statement.isEmpty()) {
      statements.add(statement);
    }
  }

  private String quote(String name) {
    return "\"" + name + "\"";
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
