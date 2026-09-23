/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import java.nio.file.Path;

record NdjsonViewerArguments(
    Path input,
    Path database,
    Path output,
    String sql,
    QueryOutputFormat format,
    boolean topics,
    boolean interactive,
    boolean ui,
    boolean mcp) {

  static NdjsonViewerArguments parse(String[] args) {
    if (args.length == 0) {
      throw new HelpRequestedException();
    }

    Path input = null;
    Path database = null;
    Path output = null;
    String sql = null;
    QueryOutputFormat format = QueryOutputFormat.TABLE;
    boolean topics = false;
    boolean interactive = false;
    boolean ui = false;
    boolean mcp = false;

    int firstOption = 0;
    if (!args[0].startsWith("-")) {
      input = Path.of(args[0]);
      firstOption = 1;
    }

    for (int index = firstOption; index < args.length; index++) {
      String option = args[index];
      switch (option) {
        case "--database" -> database = Path.of(requireValue(args, ++index, option));
        case "--output" -> output = Path.of(requireValue(args, ++index, option));
        case "--sql" -> sql = requireValue(args, ++index, option);
        case "--format" -> format = QueryOutputFormat.parse(requireValue(args, ++index, option));
        case "--raw" -> format = QueryOutputFormat.RAW;
        case "--topics" -> topics = true;
        case "--interactive" -> interactive = true;
        case "-ui", "--ui" -> ui = true;
        case "--mcp" -> mcp = true;
        case "--help", "-h" -> throw new HelpRequestedException();
        default -> throw new IllegalArgumentException("Unknown option: " + option);
      }
    }

    if (input == null && database == null) {
      throw new IllegalArgumentException(
          "Either an input file/directory or --database <file> is required");
    }

    int operationCount =
        (topics ? 1 : 0)
            + (sql == null ? 0 : 1)
            + (interactive ? 1 : 0)
            + (ui ? 1 : 0)
            + (mcp ? 1 : 0);
    if (operationCount > 1) {
      throw new IllegalArgumentException(
          "--topics, --sql, --interactive, -ui/--ui and --mcp are mutually exclusive");
    }
    if (output != null && format == QueryOutputFormat.TABLE) {
      format = inferOutputFormat(output);
    }

    return new NdjsonViewerArguments(
        input, database, output, sql, format, topics, interactive, ui, mcp);
  }

  private static String requireValue(String[] args, int index, String option) {
    if (index >= args.length) {
      throw new IllegalArgumentException("Missing value for " + option);
    }
    return args[index];
  }

  private static QueryOutputFormat inferOutputFormat(Path output) {
    String filename = output.getFileName().toString().toLowerCase();
    return filename.endsWith(".csv") ? QueryOutputFormat.CSV : QueryOutputFormat.NDJSON;
  }

  static final class HelpRequestedException extends RuntimeException {}
}
