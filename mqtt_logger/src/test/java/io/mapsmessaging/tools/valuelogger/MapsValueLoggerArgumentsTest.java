package io.mapsmessaging.tools.valuelogger;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MapsValueLoggerArgumentsTest {

  @Test
  void allCliArgsStillWork() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883", "--topic", "/test",
                     "--qos", "1", "--output", "out.csv"},
        key -> null);
    assertEquals("tcp://localhost:1883", args.getUrl());
    assertEquals("/test", args.getTopic());
    assertEquals(1, args.getQos());
    assertEquals("out.csv", args.getOutputFileName());
    assertNull(args.getOutputDir());
    assertEquals(OutputFormat.CSV, args.getOutputFormat());
    assertEquals(500, args.getDiskWarnMb());
  }

  @Test
  void urlFromEnvVar() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{},
        Map.of("MAPS_URL", "tcp://127.0.0.1:1883")::get);
    assertEquals("tcp://127.0.0.1:1883", args.getUrl());
  }

  @Test
  void throwsWhenUrlMissingFromBothCliAndEnv() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(new String[]{}, key -> null));
    assertTrue(ex.getMessage().contains("--url"));
  }

  @Test
  void defaultsAppliedWhenNothingProvided() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        key -> null);
    assertEquals("/#", args.getTopic());
    assertEquals(1, args.getQos());
    assertEquals(OutputFormat.CSV, args.getOutputFormat());
    assertEquals("/var/log/maps-logger", args.getOutputDir());
    assertEquals(500, args.getDiskWarnMb());
    assertNull(args.getOutputFileName());
  }

  @Test
  void outputDirFromCliActivatesRollingMode() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883", "--output-dir", "/tmp/logs"},
        key -> null);
    assertEquals("/tmp/logs", args.getOutputDir());
    assertNull(args.getOutputFileName());
  }

  @Test
  void outputDirFromEnvActivatesRollingMode() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        Map.of("MAPS_OUTPUT_DIR", "/data/logs")::get);
    assertEquals("/data/logs", args.getOutputDir());
  }

  @Test
  void outputFileDisablesRollingModeWhenOutputDirNotSet() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883",
                     "--topic", "/#", "--qos", "1", "--output", "out.csv"},
        key -> null);
    assertNull(args.getOutputDir());
    assertEquals("out.csv", args.getOutputFileName());
  }

  @Test
  void cliArgOverridesEnvVar() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883", "--topic", "/cli-topic"},
        Map.of("MAPS_TOPIC", "/env-topic")::get);
    assertEquals("/cli-topic", args.getTopic());
  }

  @Test
  void diskWarnMbFromCli() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883", "--disk-warn-mb", "200"},
        key -> null);
    assertEquals(200, args.getDiskWarnMb());
  }

  @Test
  void diskWarnMbFromEnv() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        Map.of("MAPS_DISK_WARN_MB", "1024")::get);
    assertEquals(1024, args.getDiskWarnMb());
  }

  @Test
  void formatFromEnvVar() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        Map.of("MAPS_FORMAT", "json")::get);
    assertEquals(OutputFormat.JSON, args.getOutputFormat());
  }

  @Test
  void unknownArgumentThrows() {
    assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "tcp://localhost:1883", "--unknown"},
            key -> null));
  }

  @Test
  void cliUrlOverridesEnvVar() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://cli-host:1883"},
        Map.of("MAPS_URL", "tcp://env-host:1883")::get);
    assertEquals("tcp://cli-host:1883", args.getUrl());
  }

  @Test
  void diskWarnMbRejectsNonPositive() {
    assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "tcp://localhost:1883", "--disk-warn-mb", "0"},
            key -> null));
    assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "tcp://localhost:1883", "--disk-warn-mb", "-1"},
            key -> null));
  }
}
