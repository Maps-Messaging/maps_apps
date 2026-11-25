package io.mapsmessaging.tools.mqttreplay;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MqttReplayMainTest {
  @TempDir Path directory;

  @Test
  void decodesLoggerEventAndDefaults() {
    ReplayEvent event = NdjsonReplayDecoder.decode(
        "{\"topic\":\"a/b\",\"receivedTimestamp\":\"2026-09-01T01:00:00Z\",\"payload\":{\"value\":42}}", 1);
    assertEquals("a/b", event.topic());
    assertEquals("{\"value\":42}", new String(event.payload(), StandardCharsets.UTF_8));
    assertEquals(Instant.parse("2026-09-01T01:00:00Z"), event.timestamp());
    assertEquals(1, event.qos());
  }

  @Test
  void supportsPerTopicOptionAndRejectsInvalidSpeed() {
    ReplayOptions options = ReplayOptions.parse(
        new String[]{"--dir", directory.toString(), "--broker", "tcp://localhost:1883",
            "--speed", "100", "--session-per-topic"});
    assertEquals(100.0, options.speed());
    assertTrue(options.sessionPerTopic());
    assertThrows(IllegalArgumentException.class, () -> ReplayOptions.parse(
        new String[]{"--dir", directory.toString(), "--broker", "tcp://localhost:1883", "--speed", "0"}));
  }

  @Test
  void mergesPlainAndGzipLogsInTimestampOrder() throws Exception {
    String first = "{\"topic\":\"a\",\"receivedTimestamp\":\"2026-09-01T00:00:00Z\",\"payload\":1}\n";
    String second = "{\"topic\":\"b\",\"receivedTimestamp\":\"2026-09-01T00:00:01Z\",\"payload\":2}\n";
    Files.writeString(directory.resolve("b.ndjson"), second);
    try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("a.ndjson.gz")))) {
      output.write(first.getBytes(StandardCharsets.UTF_8));
    }
    try (NdjsonReplayReader reader = new NdjsonReplayReader(directory, 1)) {
      assertEquals("a", reader.next().topic());
      assertEquals("b", reader.next().topic());
      assertNull(reader.next());
    }
  }

  @Test
  void skipsMalformedRecordsAndContinuesWithLaterEvents() throws Exception {
    String malformed = "{\"topic\":\"bad\",\"payload\":1}\n";
    String first = "{\"topic\":\"valid\",\"receivedTimestamp\":\"2026-09-01T00:00:00Z\",\"payload\":2}\n";
    String second = "{\"topic\":\"last\",\"receivedTimestamp\":\"2026-09-01T00:00:01Z\",\"payload\":3}\n";
    Files.writeString(directory.resolve("events.ndjson"), malformed + first + "\"\\u0000\"\n" + second);
    try (NdjsonReplayReader reader = new NdjsonReplayReader(directory, 1)) {
      assertEquals("valid", reader.next().topic());
      assertEquals("last", reader.next().topic());
      assertNull(reader.next());
      assertEquals(2L, reader.skippedRecords());
    }
  }

  @Test
  void skipsWildcardPublishTopicAndContinues() throws Exception {
    String timestamp = "\"receivedTimestamp\":\"2026-09-01T00:00:00Z\"";
    Files.writeString(directory.resolve("events.ndjson"),
        "{\"topic\":\"sensors/+/data\"," + timestamp + ",\"payload\":1}\n"
            + "{\"topic\":\"sensors/device/data\"," + timestamp + ",\"payload\":2}\n");
    java.io.PrintStream previous = System.err;
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    try (java.io.PrintStream errors = new java.io.PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setErr(errors);
      try (NdjsonReplayReader reader = new NdjsonReplayReader(directory, 0)) {
        assertEquals("sensors/device/data", reader.next().topic());
        assertNull(reader.next());
        assertEquals(1L, reader.skippedRecords());
      }
    } finally {
      System.setErr(previous);
    }
    String error = output.toString(StandardCharsets.UTF_8);
    assertTrue(error.contains("events.ndjson:1"));
    assertTrue(error.contains("sensors/+/data"));
  }

  @Test
  void skipsInvalidRecordInsideGzipAndContinues() throws Exception {
    String content = "{\"topic\":\"a\",\"receivedTimestamp\":\"2026-09-01T00:00:00Z\",\"payload\":1}\n"
        + "{\"topic\":\"invalid\"}\n"
        + "{\"topic\":\"b\",\"receivedTimestamp\":\"2026-09-01T00:00:01Z\",\"payload\":2}\n";
    try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("events.ndjson.gz")))) {
      output.write(content.getBytes(StandardCharsets.UTF_8));
    }
    try (NdjsonReplayReader reader = new NdjsonReplayReader(directory, 1)) {
      assertEquals("a", reader.next().topic());
      assertEquals("b", reader.next().topic());
      assertNull(reader.next());
      assertEquals(1L, reader.skippedRecords());
    }
  }

  @Test
  void groupsNestedTopicsUnderFirstChildFolder() {
    String prefix = "/main_folder/sub_folder";
    assertEquals("/main_folder/sub_folder/drone_1",
        SessionKeyResolver.resolve(prefix + "/drone_1/position", false, prefix));
    assertEquals("/main_folder/sub_folder/drone_1",
        SessionKeyResolver.resolve(prefix + "/drone_1/status/health", false, prefix));
    assertEquals("/main_folder/sub_folder/drone_2",
        SessionKeyResolver.resolve(prefix + "/drone_2/position", false, prefix));
  }

  @Test
  void usesSharedFallbackSessionForUnmatchedTopics() {
    String prefix = "/main_folder/sub_folder";
    for (String topic : new String[]{
        "/other/drone_1/position", "/main_folder/sub_folderX/drone_1/position",
        prefix + "/drone_1", prefix + "/drone_1/", prefix + "//position",
        "/state/twins/RHIB-001"}) {
      assertEquals("", SessionKeyResolver.resolve(topic, false, prefix));
    }
  }

  @Test
  void preservesOriginalGroupingModesAndValidatesNewOption() {
    assertEquals("", SessionKeyResolver.resolve("a/b", false, null));
    assertEquals("a/b", SessionKeyResolver.resolve("a/b", true, null));
    ReplayOptions options = ReplayOptions.parse(new String[]{
        "--dir", directory.toString(), "--broker", "tcp://localhost:1883",
        "--session-prefix", "/main_folder/sub_folder/"});
    assertEquals("/main_folder/sub_folder", options.sessionPrefix());
    assertThrows(IllegalArgumentException.class, () -> ReplayOptions.parse(new String[]{
        "--dir", directory.toString(), "--broker", "tcp://localhost:1883",
        "--session-prefix", "/main_folder/sub_folder", "--session-per-topic"}));
  }
}

