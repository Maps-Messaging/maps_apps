/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.SQLException;

final class InteractiveQueryShell {

  private final DuckDbLogDatabase database;
  private final QueryResultPrinter printer;

  InteractiveQueryShell(DuckDbLogDatabase database, QueryResultPrinter printer) {
    this.database = database;
    this.printer = printer;
  }

  void run() throws IOException {
    BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
    PrintWriter output = new PrintWriter(System.out, true);
    output.println("MAPS NDJSON viewer. Query maps_log or mavlink_log; enter .help for commands.");

    while (true) {
      output.print("duckdb> ");
      output.flush();
      String command = input.readLine();
      if (command == null || ".quit".equals(command.trim()) || ".exit".equals(command.trim())) {
        return;
      }
      if (command.isBlank()) {
        continue;
      }

      try {
        switch (command.trim()) {
          case ".help" -> printHelp(output);
          case ".topics" -> execute(DuckDbLogDatabase.TOPICS_SQL, output);
          case ".mavlink-topics" -> execute(DuckDbLogDatabase.MAVLINK_TOPICS_SQL, output);
          case ".schema" -> database.columns("maps_log").forEach(output::println);
          default -> execute(command, output);
        }
      } catch (SQLException | IllegalArgumentException exception) {
        output.println("Error: " + exception.getMessage());
      }
    }
  }

  private void execute(String sql, PrintWriter output) throws SQLException {
    try (DuckDbLogDatabase.Query query = database.query(sql)) {
      if (query.hasResultSet()) {
        printer.print(query.resultSet(), QueryOutputFormat.TABLE, output);
      } else {
        output.println("Statement completed.");
      }
    }
  }

  private void printHelp(PrintWriter output) {
    output.println(".topics          list all MAPS topics");
    output.println(".mavlink-topics  list MAVLink topics");
    output.println(".schema          describe maps_log");
    output.println(".quit             exit");
    output.println("Any other line is executed as DuckDB SQL. Available relations: maps_log, mavlink_log.");
  }
}
