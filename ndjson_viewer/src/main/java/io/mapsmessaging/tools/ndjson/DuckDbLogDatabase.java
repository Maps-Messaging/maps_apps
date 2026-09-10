/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

final class DuckDbLogDatabase implements AutoCloseable {

  static final String TOPICS_SQL =
      "SELECT topic, count(*) AS records FROM maps_log GROUP BY topic ORDER BY topic";
  static final String MAVLINK_TOPICS_SQL =
      "SELECT topic, count(*) AS records FROM mavlink_log GROUP BY topic ORDER BY topic";

  private final Connection connection;

  DuckDbLogDatabase(Path database) throws SQLException, IOException {
    if (database == null) {
      connection = DriverManager.getConnection("jdbc:duckdb:");
      return;
    }

    Path absoluteDatabase = database.toAbsolutePath().normalize();
    Path parent = absoluteDatabase.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    connection = DriverManager.getConnection("jdbc:duckdb:" + absoluteDatabase);
  }

  long load(List<Path> inputFiles) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP VIEW IF EXISTS mavlink_log");
      statement.execute("DROP VIEW IF EXISTS maps_log");
      statement.execute("DROP TABLE IF EXISTS raw_log");
      statement.execute(
          "CREATE TABLE raw_log AS SELECT * FROM read_ndjson_auto("
              + toDuckDbFileList(inputFiles)
              + ", union_by_name = true, filename = true, ignore_errors = true)");

      boolean hasOpaqueData = hasColumn("raw_log", "opaqueData");
      boolean hasTopic = hasColumn("raw_log", "topic");
      String decodedColumns = hasOpaqueData
          ? ", try(decode(from_base64(opaqueData))) AS decoded_text"
              + ", try_cast(try(decode(from_base64(opaqueData))) AS JSON) AS payload"
          : ", NULL::VARCHAR AS decoded_text, NULL::JSON AS payload";
      statement.execute("CREATE VIEW maps_log AS SELECT *" + decodedColumns + " FROM raw_log");
      statement.execute(
          hasTopic
              ? "CREATE VIEW mavlink_log AS SELECT * FROM maps_log WHERE lower(coalesce(topic, '')) LIKE '%mavlink%'"
              : "CREATE VIEW mavlink_log AS SELECT * FROM maps_log WHERE false");

      try (ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM raw_log")) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  Query query(String sql) throws SQLException {
    Statement statement = connection.createStatement();
    try {
      boolean hasResultSet = statement.execute(sql);
      ResultSet resultSet = hasResultSet ? statement.getResultSet() : null;
      return new Query(statement, resultSet, statement.getLargeUpdateCount());
    } catch (SQLException exception) {
      statement.close();
      throw exception;
    }
  }

  record Query(Statement statement, ResultSet resultSet, long updateCount)
      implements AutoCloseable {

    boolean hasResultSet() {
      return resultSet != null;
    }

    @Override
    public void close() throws SQLException {
      statement.close();
    }
  }

  List<String> columns(String relation) throws SQLException {
    if (!relation.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid relation name: " + relation);
    }
    try (Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery("DESCRIBE " + relation)) {
      java.util.ArrayList<String> columns = new java.util.ArrayList<>();
      while (resultSet.next()) {
        columns.add(resultSet.getString("column_name") + " " + resultSet.getString("column_type"));
      }
      return List.copyOf(columns);
    }
  }

  private boolean hasColumn(String table, String column) throws SQLException {
    return columns(table).stream()
        .map(value -> value.substring(0, value.indexOf(' ')).toLowerCase(Locale.ROOT))
        .anyMatch(column.toLowerCase(Locale.ROOT)::equals);
  }

  private String toDuckDbFileList(List<Path> inputFiles) {
    return inputFiles.stream()
        .map(Path::toString)
        .map(this::quoteSqlString)
        .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
  }

  private String quoteSqlString(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  @Override
  public void close() throws SQLException {
    connection.close();
  }
}
