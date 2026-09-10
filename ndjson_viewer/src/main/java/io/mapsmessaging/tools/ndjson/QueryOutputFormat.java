/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

enum QueryOutputFormat {
  TABLE,
  NDJSON,
  CSV;

  static QueryOutputFormat parse(String value) {
    return switch (value.toLowerCase()) {
      case "table" -> TABLE;
      case "ndjson", "json" -> NDJSON;
      case "csv" -> CSV;
      default -> throw new IllegalArgumentException("Unknown output format: " + value);
    };
  }
}
