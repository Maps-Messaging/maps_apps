/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class MapsNdjsonViewerMain {

  private MapsNdjsonViewerMain() {}

  public static void main(String[] args) {
    int exitCode = run(args);
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  static int run(String[] args) {
    NdjsonViewerArguments arguments;
    try {
      arguments = NdjsonViewerArguments.parse(args);
    } catch (NdjsonViewerArguments.HelpRequestedException exception) {
      printUsage(System.out);
      return 0;
    } catch (IllegalArgumentException exception) {
      System.err.println(exception.getMessage());
      printUsage(System.err);
      return 2;
    }

    try {
      List<java.nio.file.Path> inputFiles = new InputFileResolver().resolve(arguments.input());
      try (DuckDbLogDatabase database = new DuckDbLogDatabase(arguments.database())) {
        long records = database.load(inputFiles);
        System.err.printf("Loaded %,d record(s) from %,d file(s)%n", records, inputFiles.size());

        if (arguments.topics()) {
          execute(database, DuckDbLogDatabase.TOPICS_SQL, arguments);
        } else if (arguments.sql() != null) {
          execute(database, arguments.sql(), arguments);
        } else if (System.console() != null) {
          new InteractiveQueryShell(database, new QueryResultPrinter()).run();
        } else {
          execute(database, DuckDbLogDatabase.TOPICS_SQL, arguments);
        }
      }
      return 0;
    } catch (Exception exception) {
      System.err.println("Unable to inspect NDJSON log: " + exception.getMessage());
      return 1;
    }
  }

  private static void execute(
      DuckDbLogDatabase database,
      String sql,
      NdjsonViewerArguments arguments) throws Exception {
    try (PrintWriter output = createOutput(arguments);
         DuckDbLogDatabase.Query query = database.query(sql)) {
      new QueryResultPrinter().print(query.resultSet(), arguments.format(), output);
    }
  }

  private static PrintWriter createOutput(NdjsonViewerArguments arguments) throws IOException {
    if (arguments.output() == null) {
      return new PrintWriter(System.out, true) {
        @Override
        public void close() {
          flush();
        }
      };
    }
    java.nio.file.Path parent = arguments.output().toAbsolutePath().normalize().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    return new PrintWriter(Files.newBufferedWriter(arguments.output(), StandardCharsets.UTF_8));
  }

  private static void printUsage(java.io.PrintStream output) {
    output.println("Usage: maps-ndjson-viewer <file-or-directory> [options]");
    output.println("  --topics                 list topics and record counts");
    output.println("  --sql <query>            execute SQL against maps_log or mavlink_log");
    output.println("  --database <file>        persist the imported data in a DuckDB database");
    output.println("  --format table|ndjson|csv");
    output.println("  --output <file>          write query results to a file");
    output.println();
    output.println("With no query in an interactive terminal, an SQL prompt is opened.");
  }
}
