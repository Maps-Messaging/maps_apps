package io.mapsmessaging.tools.valuelogger;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapsValueLoggerArgumentsTest {

  @Test
  void allCliArgsStillWork() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883", "--topic", "/test",
                     "--qos", "1", "--output", "out.csv"},
        key -> null);
    assertEquals("tcp://localhost:1883", args.getUrl());
    assertEquals(List.of("/test"), args.getTopics());
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
        Map.of("MAPS_LOGGER_URL", "tcp://127.0.0.1:1883")::get);
    assertEquals("tcp://127.0.0.1:1883", args.getUrl());
  }

  @Test
  void throwsWhenUrlMissingFromBothCliAndEnv() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(new String[]{}, key -> null));
    assertTrue(ex.getMessage().contains("MAPS_LOGGER_URL"));
  }

  @Test
  void defaultsAppliedWhenNothingProvided() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        key -> null);
    assertEquals(List.of("#"), args.getTopics());
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
        Map.of("MAPS_LOGGER_OUTPUT_DIR", "/data/logs")::get);
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
        Map.of("MAPS_LOGGER_TOPIC", "/env-topic")::get);
    assertEquals(List.of("/cli-topic"), args.getTopics());
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
        Map.of("MAPS_LOGGER_DISK_WARN_MB", "1024")::get);
    assertEquals(1024, args.getDiskWarnMb());
  }

  @Test
  void formatFromEnvVar() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        Map.of("MAPS_LOGGER_FORMAT", "json")::get);
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
        Map.of("MAPS_LOGGER_URL", "tcp://env-host:1883")::get);
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

  @TempDir
  Path tempDir;

  @Test
  void commaSeparatedTopicsAreSplitAndTrimmed() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883", "--topic", "4817/#, /tak/cot/#,,"},
        key -> null);
    assertEquals(List.of("4817/#", "/tak/cot/#"), args.getTopics());
  }

  @Test
  void blankTopicListThrows() {
    assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "tcp://localhost:1883", "--topic", " , "},
            key -> null));
  }

  @Test
  void credentialsFromEnvVars() throws Exception {
    Path passwordFile = secretFile("password");
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://127.0.0.1:1895"},
        Map.of(
            "MAPS_LOGGER_USERNAME", "maps-logger",
            "MAPS_LOGGER_PASSWORD_FILE", passwordFile.toString())::get);
    assertEquals("maps-logger", args.getUsername());
    assertEquals(passwordFile.toString(), args.getPasswordFile());
  }

  @Test
  void securityDefaultsToNone() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        key -> null);
    assertNull(args.getUsername());
    assertNull(args.getPasswordFile());
    assertNull(args.getKeyStore());
    assertNull(args.getTrustStore());
    assertFalse(args.isTlsUrl());
  }

  @Test
  void tlsStoresFromCli() throws Exception {
    Path keyStore = secretFile("keystore");
    Path trustStore = secretFile("truststore");
    Path storePassword = secretFile("store-password");
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "SSL://127.0.0.1:1895",
                     "--keystore", keyStore.toString(),
                     "--keystore-password-file", storePassword.toString(),
                     "--truststore", trustStore.toString(),
                     "--truststore-password-file", storePassword.toString()},
        key -> null);
    assertTrue(args.isTlsUrl());
    assertEquals(keyStore.toString(), args.getKeyStore());
    assertEquals(trustStore.toString(), args.getTrustStore());
  }

  @Test
  void passwordFileWithoutUsernameThrows() throws Exception {
    Path passwordFile = secretFile("password");
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "tcp://localhost:1883",
                         "--password-file", passwordFile.toString()},
            key -> null));
    assertTrue(ex.getMessage().contains("--username"));
  }

  @Test
  void storesRequireSslUrl() throws Exception {
    Path trustStore = secretFile("truststore");
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "tcp://localhost:1883",
                         "--truststore", trustStore.toString(),
                         "--truststore-password-file", trustStore.toString()},
            key -> null));
    assertTrue(ex.getMessage().contains("ssl://"));
  }

  @Test
  void storesRequirePasswordFiles() throws Exception {
    Path store = secretFile("store");
    assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "ssl://localhost:1895", "--keystore", store.toString()},
            key -> null));
    assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "ssl://localhost:1895", "--truststore", store.toString()},
            key -> null));
  }

  @Test
  void unreadableSecretFileThrows() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
        MapsValueLoggerArguments.parse(
            new String[]{"--url", "tcp://localhost:1883", "--username", "maps-logger",
                         "--password-file", tempDir.resolve("missing").toString()},
            key -> null));
    assertTrue(ex.getMessage().contains("--password-file"));
  }

  private Path secretFile(String name) throws Exception {
    return Files.writeString(tempDir.resolve(name), "secret\n");
  }
}
