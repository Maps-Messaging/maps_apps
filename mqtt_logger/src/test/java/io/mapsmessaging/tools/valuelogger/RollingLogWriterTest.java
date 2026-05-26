package io.mapsmessaging.tools.valuelogger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollingLogWriterTest {

  @TempDir
  Path tempDir;

  @Test
  void createsFileNamedForCurrentHour() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-05-26T10:30:00Z"), ZoneOffset.UTC);
    RollingLogWriter writer = new RollingLogWriter(tempDir, OutputFormat.CSV, 500, clock,
        () -> Long.MAX_VALUE);

    writer.open();
    writer.write(buildRecord());
    writer.close();

    assertTrue(Files.exists(tempDir.resolve("maps-2026-05-26-10.csv")));
  }

  @Test
  void createsNdjsonFileWhenFormatIsJson() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-05-26T10:30:00Z"), ZoneOffset.UTC);
    RollingLogWriter writer = new RollingLogWriter(tempDir, OutputFormat.JSON, 500, clock,
        () -> Long.MAX_VALUE);

    writer.open();
    writer.write(buildRecord());
    writer.close();

    assertTrue(Files.exists(tempDir.resolve("maps-2026-05-26-10.ndjson")));
  }

  @Test
  void rollsToNewFileWhenHourChanges() throws Exception {
    Clock clock = mock(Clock.class);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant())
        .thenReturn(Instant.parse("2026-05-26T10:30:00Z"))
        .thenReturn(Instant.parse("2026-05-26T10:30:01Z"))
        .thenReturn(Instant.parse("2026-05-26T11:00:01Z"));

    RollingLogWriter writer = new RollingLogWriter(tempDir, OutputFormat.CSV, 500, clock,
        () -> Long.MAX_VALUE);

    writer.open();
    writer.write(buildRecord()); // hour 10
    writer.write(buildRecord()); // hour 11 — triggers roll
    writer.close();

    assertTrue(Files.exists(tempDir.resolve("maps-2026-05-26-10.csv")));
    assertTrue(Files.exists(tempDir.resolve("maps-2026-05-26-11.csv")));
  }

  @Test
  void writesWarningToStderrWhenDiskSpaceBelowThreshold() throws Exception {
    Clock rollClock = mock(Clock.class);
    when(rollClock.getZone()).thenReturn(ZoneOffset.UTC);
    when(rollClock.instant())
        .thenReturn(Instant.parse("2026-05-26T10:30:00Z"))
        .thenReturn(Instant.parse("2026-05-26T11:00:01Z")); // triggers roll + disk check

    // Disk space supplier returns 100 MB, threshold is 500 MB → warning expected
    long freeMb = 100L * 1024 * 1024;

    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured));

    try {
      RollingLogWriter writer = new RollingLogWriter(tempDir, OutputFormat.CSV, 500, rollClock,
          () -> freeMb);
      writer.open();
      writer.write(buildRecord());
      writer.close();
    } finally {
      System.setErr(originalErr);
    }

    String output = captured.toString();
    assertTrue(output.contains("MAPS Logger WARNING: low disk space"),
        "Expected disk space warning in: " + output);
    assertTrue(output.contains("100 MB free"));
    assertTrue(output.contains("threshold 500 MB"));
  }

  @Test
  void noWarningWhenDiskSpaceAboveThreshold() throws Exception {
    Clock clock = mock(Clock.class);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    when(clock.instant())
        .thenReturn(Instant.parse("2026-05-26T10:30:00Z"))
        .thenReturn(Instant.parse("2026-05-26T11:00:01Z"));

    long freeMb = 2000L * 1024 * 1024; // 2000 MB, well above 500 MB threshold

    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured));

    try {
      RollingLogWriter writer = new RollingLogWriter(tempDir, OutputFormat.CSV, 500, clock,
          () -> freeMb);
      writer.open();
      writer.write(buildRecord());
      writer.close();
    } finally {
      System.setErr(originalErr);
    }

    assertFalse(captured.toString().contains("WARNING"),
        "No warning expected when disk space is sufficient");
  }

  @Test
  void createsOutputDirectoryIfAbsent() throws Exception {
    Path nested = tempDir.resolve("a/b/c");
    Clock clock = Clock.fixed(Instant.parse("2026-05-26T10:00:00Z"), ZoneOffset.UTC);

    RollingLogWriter writer = new RollingLogWriter(nested, OutputFormat.CSV, 500, clock,
        () -> Long.MAX_VALUE);
    writer.open();
    writer.close();

    assertTrue(Files.isDirectory(nested));
  }

  @Test
  void csvFileContainsHeaderRow() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-05-26T10:00:00Z"), ZoneOffset.UTC);
    RollingLogWriter writer = new RollingLogWriter(tempDir, OutputFormat.CSV, 500, clock,
        () -> Long.MAX_VALUE);

    writer.open();
    writer.write(buildRecord());
    writer.close();

    String content = Files.readString(tempDir.resolve("maps-2026-05-26-10.csv"));
    assertTrue(content.startsWith("receivedTimestamp,"),
        "CSV must start with header row");
  }

  private JsonObject buildRecord() {
    JsonObject record = new JsonObject();
    record.addProperty("receivedTimestamp", "2026-05-26T10:30:00Z");
    record.addProperty("serverTimestamp", "2026-05-26T10:29:59Z");
    record.addProperty("serverTimeMs", 1748255999000L);
    record.addProperty("latencyMs", 1000L);
    record.addProperty("topic", "/test");
    record.addProperty("contentType", "application/json");
    record.addProperty("identifier", 1);
    record.addProperty("qos", "AT_LEAST_ONCE");
    record.addProperty("protocol", "MQTT");
    record.addProperty("sessionId", "127.0.0.1_12345_255");
    record.addProperty("metaVersion", "1");
    return record;
  }
}
