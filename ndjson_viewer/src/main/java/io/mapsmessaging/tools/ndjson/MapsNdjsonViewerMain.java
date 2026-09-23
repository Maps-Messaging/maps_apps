/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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
      if (arguments.input() != null) {
        List<Path> inputFiles = new InputFileResolver().resolve(arguments.input());
        try (DuckDbLogDatabase database = new DuckDbLogDatabase(arguments.database())) {
          long records = database.load(inputFiles);
          System.err.printf("Loaded %,d record(s) from %,d file(s)%n", records, inputFiles.size());
          inspect(database, arguments);
        }
      } else {
        Path databasePath = arguments.database().toAbsolutePath().normalize();
        if (!Files.isRegularFile(databasePath)) {
          throw new IllegalArgumentException("DuckDB database does not exist: " + databasePath);
        }
        try (DuckDbLogDatabase database = new DuckDbLogDatabase(databasePath)) {
          System.err.printf("Opened DuckDB database %s%n", databasePath);
          inspect(database, arguments);
        }
      }
      return 0;
    } catch (Exception exception) {
      System.err.println("Unable to inspect log data: " + exception.getMessage());
      return 1;
    }
  }

  private static void inspect(
      DuckDbLogDatabase database,
      NdjsonViewerArguments arguments) throws Exception {
    if (arguments.topics()) {
      execute(database, DuckDbLogDatabase.TOPICS_SQL, arguments);
    } else if (arguments.sql() != null) {
      execute(database, arguments.sql(), arguments);
    } else if (arguments.ui()) {
      runUi(database);
    } else if (arguments.mcp()) {
      new McpServerRunner(database).run();
    } else if (arguments.interactive() || System.console() != null) {
      new InteractiveQueryShell(database, new QueryResultPrinter()).run();
    } else {
      execute(database, DuckDbLogDatabase.TOPICS_SQL, arguments);
    }
  }

  private static void runUi(DuckDbLogDatabase database) throws Exception {
    try (DuckDbLogDatabase.Query ignored = database.query("CALL start_ui()")) {
      // Starting the UI returns immediately. Keep the database open below.
    }
    System.err.println("DuckDB UI started in the default browser. Press Ctrl-C to stop.");
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static void execute(
      DuckDbLogDatabase database,
      String sql,
      NdjsonViewerArguments arguments) throws Exception {
    try (PrintWriter output = createOutput(arguments);
         DuckDbLogDatabase.Query query = database.query(sql)) {
      if (query.hasResultSet()) {
        new QueryResultPrinter().print(query.resultSet(), arguments.format(), output);
      } else {
        output.println("Statement completed.");
      }
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
    Path parent = arguments.output().toAbsolutePath().normalize().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    return new PrintWriter(Files.newBufferedWriter(arguments.output(), StandardCharsets.UTF_8));
  }

  private static void printUsage(java.io.PrintStream output) {
    output.println("Usage: maps-ndjson-viewer [file-or-directory] [options]");
    output.println("  --topics                 list topics and record counts");
    output.println("  --sql <query>            execute SQL against maps_log or mavlink_log");
    output.println("  --interactive            open the SQL prompt, including inside an IDE");
    output.println("  -ui, --ui                open the DuckDB UI in the default browser");
    output.println("  --mcp                    expose the loaded DuckDB data as a stdio MCP server");
    output.println("  --database <file>        persist imports or open an existing DuckDB database");
    output.println("  --format table|ndjson|csv|raw");
    output.println("  --raw                    emit a single selected column without a result envelope");
    output.println("  --output <file>          write query results to a file");
    output.println();
    output.println("Without an input path, --database must name an existing database.");
    output.println("With no query in an interactive terminal, an SQL prompt is opened.");
  }
}
