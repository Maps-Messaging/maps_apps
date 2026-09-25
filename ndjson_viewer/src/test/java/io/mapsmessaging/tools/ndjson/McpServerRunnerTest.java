/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServerRunnerTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void limitsReadOnlyQueryResults() throws Exception {
    Path input = temporaryDirectory.resolve("maps.ndjson");
    Files.writeString(
        input,
        envelope("mavlink/1/ATTITUDE", "{\"yaw\":1.25}")
            + envelope("mavlink/1/GPS_RAW_INT", "{\"fixType\":3}"));

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(null)) {
      database.load(List.of(input));
      McpServerRunner runner = new McpServerRunner(database);

      String result =
          runner.executeReadOnlyQuery("SELECT topic FROM maps_log ORDER BY topic", 1);

      var json = JsonParser.parseString(result).getAsJsonObject();
      assertEquals(1, json.get("row_count").getAsInt());
      assertEquals(1, json.getAsJsonArray("rows").size());
    }
  }

  @Test
  void acceptsMutationWordsInsideStringLiterals() {
    assertTrue(McpServerRunner.isReadOnlySql(
        "SELECT * FROM maps_log WHERE decoded_text LIKE '%UPDATE%'"));
    assertTrue(McpServerRunner.isReadOnlySql(
        "WITH sample AS (SELECT 1 AS value) SELECT value FROM sample"));
  }

  @Test
  void rejectsMutatingStatements() {
    assertFalse(McpServerRunner.isReadOnlySql("DELETE FROM maps_log"));
    assertFalse(McpServerRunner.isReadOnlySql(
        "WITH doomed AS (SELECT 1) DELETE FROM raw_log"));
    assertFalse(McpServerRunner.isReadOnlySql(
        "SELECT 1; DROP TABLE raw_log"));
  }

  @Test
  void describesRelationColumns() throws Exception {
    Path input = temporaryDirectory.resolve("maps.ndjson");
    Files.writeString(input, envelope("4817/task", "{\"state\":\"ACTIVE\"}"));

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(null)) {
      database.load(List.of(input));
      String result = new McpServerRunner(database).describeRelation("maps_log");

      var json = JsonParser.parseString(result).getAsJsonObject();
      assertEquals("maps_log", json.get("relation").getAsString());
      assertTrue(json.getAsJsonArray("columns").toString().contains("payload JSON"));
    }
  }

  @Test
  void includesExplicitBindAddressInAllowedHosts() {
    assertTrue(McpServerRunner.allowedHttpHosts("192.0.2.10").contains("192.0.2.10"));
    assertTrue(McpServerRunner.allowedHttpHosts("127.0.0.1").contains("localhost"));
  }

  private String envelope(String topic, String payload) {
    String opaqueData =
        Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return "{\"receivedTimestamp\":\"2026-09-23T08:00:00Z\","
        + "\"topic\":\"" + topic + "\",\"opaqueData\":\"" + opaqueData + "\"}\n";
  }
}
