/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapsNdjsonViewerMainTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void opensExistingDatabaseWithoutReloading() throws Exception {
    Path input = temporaryDirectory.resolve("maps.ndjson");
    Path databasePath = temporaryDirectory.resolve("existing.duckdb");
    Path output = temporaryDirectory.resolve("result.jsonl");
    Files.writeString(input, envelope("4817/task", "{\"taskId\":\"task-1\"}"));

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(databasePath)) {
      database.load(List.of(input));
      try (DuckDbLogDatabase.Query query = database.query("CREATE TABLE marker AS SELECT 42 AS value")) {
        assertFalse(query.hasResultSet());
      }
    }

    assertEquals(
        0,
        MapsNdjsonViewerMain.run(
            new String[] {
                "--database",
                databasePath.toString(),
                "--sql",
                "SELECT value FROM marker",
                "--raw",
                "--output",
                output.toString()
            }));
    assertEquals("42\n", Files.readString(output));

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(databasePath);
         DuckDbLogDatabase.Query query = database.query("SELECT count(*) FROM maps_log")) {
      query.resultSet().next();
      assertEquals(1, query.resultSet().getLong(1));
    }
  }

  @Test
  void doesNotCreateMissingDatabaseInOpenExistingMode() {
    Path databasePath = temporaryDirectory.resolve("missing.duckdb");

    assertEquals(
        1,
        MapsNdjsonViewerMain.run(
            new String[] {"--database", databasePath.toString(), "--sql", "SELECT 1"}));
    assertFalse(Files.exists(databasePath));
  }

  private String envelope(String topic, String payload) {
    String opaqueData = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return "{\"receivedTimestamp\":\"2026-09-09T16:00:01.806475630Z\","
        + "\"topic\":\"" + topic + "\",\"opaqueData\":\"" + opaqueData + "\"}\n";
  }
}
