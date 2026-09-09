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
    boolean topics) {

  static NdjsonViewerArguments parse(String[] args) {
    if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
      throw new HelpRequestedException();
    }

    Path input = Path.of(args[0]);
    Path database = null;
    Path output = null;
    String sql = null;
    QueryOutputFormat format = QueryOutputFormat.TABLE;
    boolean topics = false;

    for (int index = 1; index < args.length; index++) {
      String option = args[index];
      switch (option) {
        case "--database" -> database = Path.of(requireValue(args, ++index, option));
        case "--output" -> output = Path.of(requireValue(args, ++index, option));
        case "--sql" -> sql = requireValue(args, ++index, option);
        case "--format" -> format = QueryOutputFormat.parse(requireValue(args, ++index, option));
        case "--topics" -> topics = true;
        case "--help", "-h" -> throw new HelpRequestedException();
        default -> throw new IllegalArgumentException("Unknown option: " + option);
      }
    }

    if (topics && sql != null) {
      throw new IllegalArgumentException("--topics and --sql are mutually exclusive");
    }
    if (output != null && format == QueryOutputFormat.TABLE) {
      format = inferOutputFormat(output);
    }

    return new NdjsonViewerArguments(input, database, output, sql, format, topics);
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
