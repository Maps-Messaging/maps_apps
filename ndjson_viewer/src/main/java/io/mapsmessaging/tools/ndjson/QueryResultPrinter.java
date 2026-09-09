/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Base64;

final class QueryResultPrinter {

  private static final int TABLE_VALUE_LIMIT = 120;

  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

  long print(ResultSet resultSet, QueryOutputFormat format, PrintWriter output) throws SQLException {
    return switch (format) {
      case TABLE -> printTable(resultSet, output);
      case NDJSON -> printNdjson(resultSet, output);
      case CSV -> printCsv(resultSet, output);
    };
  }

  private long printTable(ResultSet resultSet, PrintWriter output) throws SQLException {
    ResultSetMetaData metadata = resultSet.getMetaData();
    int columnCount = metadata.getColumnCount();
    String[] headings = new String[columnCount];
    for (int column = 1; column <= columnCount; column++) {
      headings[column - 1] = metadata.getColumnLabel(column);
    }
    output.println(String.join(" | ", headings));
    output.println("-".repeat(Math.min(240, String.join(" | ", headings).length())));

    long rows = 0;
    while (resultSet.next()) {
      String[] values = new String[columnCount];
      for (int column = 1; column <= columnCount; column++) {
        values[column - 1] = truncate(toDisplayValue(resultSet.getObject(column)));
      }
      output.println(String.join(" | ", values));
      rows++;
    }
    output.printf("%n%,d row(s)%n", rows);
    output.flush();
    return rows;
  }

  private long printNdjson(ResultSet resultSet, PrintWriter output) throws SQLException {
    ResultSetMetaData metadata = resultSet.getMetaData();
    int columnCount = metadata.getColumnCount();
    long rows = 0;
    while (resultSet.next()) {
      JsonObject row = new JsonObject();
      for (int column = 1; column <= columnCount; column++) {
        addJsonValue(
            row,
            metadata.getColumnLabel(column),
            metadata.getColumnTypeName(column),
            resultSet.getObject(column));
      }
      output.println(gson.toJson(row));
      rows++;
    }
    output.flush();
    return rows;
  }

  private long printCsv(ResultSet resultSet, PrintWriter output) throws SQLException {
    ResultSetMetaData metadata = resultSet.getMetaData();
    int columnCount = metadata.getColumnCount();
    for (int column = 1; column <= columnCount; column++) {
      if (column > 1) {
        output.print(',');
      }
      output.print(csv(metadata.getColumnLabel(column)));
    }
    output.println();

    long rows = 0;
    while (resultSet.next()) {
      for (int column = 1; column <= columnCount; column++) {
        if (column > 1) {
          output.print(',');
        }
        output.print(csv(toDisplayValue(resultSet.getObject(column))));
      }
      output.println();
      rows++;
    }
    output.flush();
    return rows;
  }

  private void addJsonValue(JsonObject row, String name, String typeName, Object value) {
    if (value == null) {
      row.add(name, JsonNull.INSTANCE);
    } else if ("JSON".equalsIgnoreCase(typeName)) {
      try {
        row.add(name, JsonParser.parseString(value.toString()));
      } catch (JsonSyntaxException exception) {
        row.addProperty(name, value.toString());
      }
    } else if (value instanceof Boolean booleanValue) {
      row.addProperty(name, booleanValue);
    } else if (value instanceof Number numberValue) {
      row.addProperty(name, numberValue);
    } else if (value instanceof byte[] bytes) {
      row.addProperty(name, Base64.getEncoder().encodeToString(bytes));
    } else {
      row.addProperty(name, value.toString());
    }
  }

  private String toDisplayValue(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof byte[] bytes) {
      return Base64.getEncoder().encodeToString(bytes);
    }
    return value.toString().replace('\n', ' ').replace('\r', ' ');
  }

  private String truncate(String value) {
    return value.length() <= TABLE_VALUE_LIMIT
        ? value
        : value.substring(0, TABLE_VALUE_LIMIT - 1) + "…";
  }

  private String csv(String value) {
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
