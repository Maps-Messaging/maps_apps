/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuckDbLogDatabaseTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void loadsAndDecodesMapsEnvelope() throws Exception {
    Path input = temporaryDirectory.resolve("maps.ndjson");
    Files.writeString(input, envelope("mavlink/1/GLOBAL_POSITION_INT", "{\"heading\":123,\"velocity\":0.4}"));

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(null)) {
      assertEquals(1, database.load(List.of(input)));

      try (DuckDbLogDatabase.Query query = database.query(
          "SELECT topic, json_extract_string(payload, '$.heading') AS heading FROM mavlink_log")) {
        var resultSet = query.resultSet();
        resultSet.next();
        assertEquals("mavlink/1/GLOBAL_POSITION_INT", resultSet.getString("topic"));
        assertEquals("123", resultSet.getString("heading"));
      }
    }
  }

  @Test
  void invalidBase64DoesNotAbortImport() throws Exception {
    Path input = temporaryDirectory.resolve("maps.ndjson");
    Files.writeString(input, "{\"topic\":\"mavlink/1/STATUSTEXT\",\"opaqueData\":\"not base64!\"}\n");

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(null)) {
      assertEquals(1, database.load(List.of(input)));

      try (DuckDbLogDatabase.Query query = database.query("SELECT decoded_text, payload FROM maps_log")) {
        var resultSet = query.resultSet();
        resultSet.next();
        assertNull(resultSet.getObject("decoded_text"));
        assertNull(resultSet.getObject("payload"));
      }
    }
  }

  @Test
  void exportsDecodedPayloadAsNestedJson() throws Exception {
    Path input = temporaryDirectory.resolve("maps.ndjson");
    Files.writeString(input, envelope("mavlink/1/ATTITUDE", "{\"yaw\":1.25}"));

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(null)) {
      database.load(List.of(input));
      StringWriter output = new StringWriter();
      try (DuckDbLogDatabase.Query query = database.query("SELECT topic, payload FROM maps_log")) {
        new QueryResultPrinter()
            .print(query.resultSet(), QueryOutputFormat.NDJSON, new PrintWriter(output));
      }

      assertTrue(output.toString().contains("\"payload\":{\"yaw\":1.25}"));
    }
  }

  @Test
  void executesAttachAndCreateTableStatements() throws Exception {
    Path input = temporaryDirectory.resolve("maps.ndjson");
    Path reviewDatabase = temporaryDirectory.resolve("review.duckdb");
    Files.writeString(input, envelope("mavlink/1/GPS_RAW_INT", "{\"fixType\":3}"));

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(null)) {
      database.load(List.of(input));
      executeStatement(database, "ATTACH '" + reviewDatabase + "' AS review");
      executeStatement(
          database,
          "CREATE TABLE review.maps_log AS SELECT topic, payload FROM maps_log");
      executeStatement(database, "DETACH review");
    }

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(reviewDatabase);
         DuckDbLogDatabase.Query query = database.query("SELECT count(*) FROM maps_log")) {
      query.resultSet().next();
      assertEquals(1, query.resultSet().getLong(1));
    }
  }

  @Test
  void resolvesAndLoadsPlainAndGzipFilesFromDirectory() throws Exception {
    Path plain = temporaryDirectory.resolve("first.ndjson");
    Path compressed = temporaryDirectory.resolve("nested/second.ndjson.gz");
    Files.createDirectories(compressed.getParent());
    Files.writeString(plain, envelope("4817/task", "{\"state\":\"ACTIVE\"}"));
    writeGzip(compressed, envelope("mavlink/1/HEARTBEAT", "{\"mode\":\"AUTO\"}"));

    List<Path> files = new InputFileResolver().resolve(temporaryDirectory);
    assertEquals(2, files.size());

    try (DuckDbLogDatabase database = new DuckDbLogDatabase(null)) {
      assertEquals(2, database.load(files));
    }
  }

  private String envelope(String topic, String payload) {
    String opaqueData = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    return "{\"receivedTimestamp\":\"2026-09-09T16:00:01.806475630Z\","
        + "\"topic\":\"" + topic + "\",\"opaqueData\":\"" + opaqueData + "\"}\n";
  }

  private void writeGzip(Path path, String value) throws IOException {
    try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(path));
         OutputStreamWriter writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
      writer.write(value);
    }
  }

  private void executeStatement(DuckDbLogDatabase database, String sql) throws Exception {
    try (DuckDbLogDatabase.Query query = database.query(sql)) {
      assertFalse(query.hasResultSet());
    }
  }
}
