# MAPS Logger Systemd & Docker Packaging — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the MAPS Value Logger as a systemd service (native hosts) and Docker Compose sidecar (aggregator), with hourly-rolling output files, env-var configuration, and a disk-space warning — deployable to any Linux architecture via a single install tarball.

**Architecture:** `MapsValueLoggerArguments` gains env-var fallback and a new `--output-dir` / `MAPS_OUTPUT_DIR` parameter that activates a `RollingLogWriter` — a `LogWriter` wrapper that switches to a new dated file on each hour boundary and checks disk space after each roll. Deployment artifacts (systemd units, env template, install script, Docker files, scripts) live in `install/` and `scripts/`.

**Tech Stack:** Java 21, Lombok, Maven 3 (maven-shade-plugin 3.6.0), JUnit Jupiter 6, Mockito 5, Bash, systemd, Docker Compose v2.

---

## File Map

### Modified
- `mqtt_logger/pom.xml` — add `ManifestResourceTransformer` to set `Main-Class`
- `mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/MapsValueLoggerArguments.java` — env-var fallback, `--output-dir`, `--disk-warn-mb`, defaults for topic/qos/format
- `mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/LogWriterFactory.java` — detect rolling mode, create `RollingLogWriter`

### Created (Java)
- `mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/RollingLogWriter.java` — hourly file rotation + disk space check

### Created (Tests)
- `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/MapsValueLoggerArgumentsTest.java`
- `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/RollingLogWriterTest.java`
- `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/LogWriterFactoryTest.java`

### Created (Deployment)
- `install/systemd/maps-logger.service`
- `install/systemd/maps-logger-compress.service`
- `install/systemd/maps-logger-compress.timer`
- `install/systemd/maps-logger.env`
- `install/install.sh`
- `install/maps-config-snippet.yaml`
- `install/docker/Dockerfile`
- `install/docker/docker-compose.yml`
- `scripts/build.sh`
- `scripts/bundle.sh`
- `scripts/deploy.sh`

---

## Task 1: Configure executable JAR (add Main-Class to manifest)

Without a `Main-Class` in the JAR manifest, `java -jar` fails. The systemd unit uses `java -jar`, so this must be fixed first.

**Files:**
- Modify: `mqtt_logger/pom.xml`

- [ ] **Step 1: Add ManifestResourceTransformer to the shade plugin configuration**

In `mqtt_logger/pom.xml`, inside the `<configuration>` block of the `maven-shade-plugin` execution, add a `<transformers>` section. The full updated `<configuration>` block becomes:

```xml
<configuration>
    <shadedArtifactAttached>false</shadedArtifactAttached>
    <createDependencyReducedPom>false</createDependencyReducedPom>
    <transformers>
        <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>io.mapsmessaging.tools.valuelogger.MapsValueLoggerMain</mainClass>
        </transformer>
    </transformers>
    <filters>
        <filter>
            <artifact>*:*</artifact>
            <excludes>
                <exclude>META-INF/*.SF</exclude>
                <exclude>META-INF/*.DSA</exclude>
                <exclude>META-INF/*.RSA</exclude>
            </excludes>
        </filter>
    </filters>
</configuration>
```

- [ ] **Step 2: Build and verify `java -jar` works**

```bash
mvn clean package -pl mqtt_logger
java -jar mqtt_logger/target/mqtt_logger-1.0.0-SNAPSHOT.jar
```

Expected output (no broker running):
```
Missing required argument: --url (or set MAPS_URL)
```
(The exact message will change in Task 2, but any error is fine — the JAR must launch without `no main manifest attribute`.)

- [ ] **Step 3: Commit**

```bash
git add mqtt_logger/pom.xml
git commit -m "feat(mqtt_logger): set Main-Class in shaded JAR manifest"
```

---

## Task 2: Extend `MapsValueLoggerArguments` with env-var fallback and new fields

**Files:**
- Modify: `mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/MapsValueLoggerArguments.java`
- Create: `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/MapsValueLoggerArgumentsTest.java`

- [ ] **Step 1: Write the failing tests**

Create `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/MapsValueLoggerArgumentsTest.java`:

```java
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
  void diskWarnMbFromCliAndEnv() {
    MapsValueLoggerArguments fromCli = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883", "--disk-warn-mb", "200"},
        key -> null);
    assertEquals(200, fromCli.getDiskWarnMb());

    MapsValueLoggerArguments fromEnv = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        Map.of("MAPS_DISK_WARN_MB", "1024")::get);
    assertEquals(1024, fromEnv.getDiskWarnMb());
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
}
```

- [ ] **Step 2: Run tests — expect compilation failures**

```bash
mvn test -pl mqtt_logger -Dtest=MapsValueLoggerArgumentsTest
```

Expected: compilation errors — `parse(String[], Function)` does not exist, `getOutputDir()` does not exist, `getDiskWarnMb()` does not exist.

- [ ] **Step 3: Rewrite `MapsValueLoggerArguments.java`**

Replace the entire file with:

```java
package io.mapsmessaging.tools.valuelogger;

import java.util.function.Function;
import lombok.Getter;

@Getter
public class MapsValueLoggerArguments {

  private final String url;
  private final String topic;
  private final int qos;
  private final String outputFileName;
  private final String outputDir;
  private final OutputFormat outputFormat;
  private final int diskWarnMb;

  private MapsValueLoggerArguments(
      String url,
      String topic,
      int qos,
      String outputFileName,
      String outputDir,
      OutputFormat outputFormat,
      int diskWarnMb) {
    this.url = url;
    this.topic = topic;
    this.qos = qos;
    this.outputFileName = outputFileName;
    this.outputDir = outputDir;
    this.outputFormat = outputFormat;
    this.diskWarnMb = diskWarnMb;
  }

  public static MapsValueLoggerArguments parse(String[] args) {
    return parse(args, System::getenv);
  }

  static MapsValueLoggerArguments parse(String[] args, Function<String, String> env) {
    String url = null;
    String topic = null;
    Integer qos = null;
    String outputFileName = null;
    String outputDir = null;
    OutputFormat requestedOutputFormat = null;
    Integer diskWarnMb = null;

    for (int index = 0; index < args.length; index++) {
      String argument = args[index];

      switch (argument) {
        case "--url":
          url = readRequiredValue(args, ++index, "--url");
          break;

        case "--topic":
          topic = readRequiredValue(args, ++index, "--topic");
          break;

        case "--qos":
          qos = parseQos(readRequiredValue(args, ++index, "--qos"));
          break;

        case "--output":
          outputFileName = readRequiredValue(args, ++index, "--output");
          break;

        case "--output-dir":
          outputDir = readRequiredValue(args, ++index, "--output-dir");
          break;

        case "--format":
          requestedOutputFormat = OutputFormat.parse(readRequiredValue(args, ++index, "--format"));
          break;

        case "--disk-warn-mb":
          diskWarnMb = parseDiskWarnMb(readRequiredValue(args, ++index, "--disk-warn-mb"));
          break;

        default:
          throw new IllegalArgumentException("Unknown argument: " + argument);
      }
    }

    // Env-var fallback, then built-in defaults
    if (url == null) {
      url = env.apply("MAPS_URL");
    }

    if (topic == null) {
      String envTopic = env.apply("MAPS_TOPIC");
      topic = (envTopic != null) ? envTopic : "/#";
    }

    if (qos == null) {
      String envQos = env.apply("MAPS_QOS");
      qos = (envQos != null) ? parseQos(envQos) : 1;
    }

    if (requestedOutputFormat == null) {
      String envFormat = env.apply("MAPS_FORMAT");
      if (envFormat != null) {
        requestedOutputFormat = OutputFormat.parse(envFormat);
      }
    }

    // --output-dir takes precedence; only default to rolling mode if --output was not provided
    if (outputDir == null && outputFileName == null) {
      String envOutputDir = env.apply("MAPS_OUTPUT_DIR");
      outputDir = (envOutputDir != null) ? envOutputDir : "/var/log/maps-logger";
    }

    if (diskWarnMb == null) {
      String envWarn = env.apply("MAPS_DISK_WARN_MB");
      diskWarnMb = (envWarn != null) ? parseDiskWarnMb(envWarn) : 500;
    }

    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("Missing required argument: --url (or set MAPS_URL)");
    }

    OutputFormat resolvedFormat = (outputDir != null)
        ? (requestedOutputFormat != null ? requestedOutputFormat : OutputFormat.CSV)
        : OutputFormat.resolve(outputFileName, requestedOutputFormat);

    return new MapsValueLoggerArguments(url, topic, qos, outputFileName, outputDir,
        resolvedFormat, diskWarnMb);
  }

  public static void printUsage() {
    System.err.println("Usage:");
    System.err.println(
        "  maps-value-logger --url <url> [--topic <topic>] [--qos <0|1|2>]");
    System.err.println(
        "    [--format <csv|json>] [--output <file> | --output-dir <dir>]");
    System.err.println(
        "    [--disk-warn-mb <mb>]");
    System.err.println();
    System.err.println(
        "Environment variables: MAPS_URL, MAPS_TOPIC, MAPS_QOS, MAPS_FORMAT,");
    System.err.println(
        "  MAPS_OUTPUT_DIR, MAPS_DISK_WARN_MB");
  }

  private static String readRequiredValue(String[] args, int index, String name) {
    if (index >= args.length) {
      throw new IllegalArgumentException("Missing value for argument: " + name);
    }

    String value = args[index];

    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Blank value for argument: " + name);
    }

    return value;
  }

  private static int parseQos(String qosText) {
    try {
      int qos = Integer.parseInt(qosText);

      if (qos < 0 || qos > 2) {
        throw new IllegalArgumentException("--qos must be 0, 1, or 2");
      }

      return qos;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("--qos must be 0, 1, or 2", exception);
    }
  }

  private static int parseDiskWarnMb(String value) {
    try {
      int mb = Integer.parseInt(value.trim());

      if (mb <= 0) {
        throw new IllegalArgumentException("--disk-warn-mb must be a positive integer");
      }

      return mb;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("--disk-warn-mb must be a positive integer", exception);
    }
  }
}
```

- [ ] **Step 4: Run tests — expect all to pass**

```bash
mvn test -pl mqtt_logger -Dtest=MapsValueLoggerArgumentsTest
```

Expected: `BUILD SUCCESS`, all 11 tests pass.

- [ ] **Step 5: Commit**

```bash
git add mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/MapsValueLoggerArguments.java \
        mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/MapsValueLoggerArgumentsTest.java
git commit -m "feat(mqtt_logger): add env-var fallback, --output-dir, --disk-warn-mb to arguments"
```

---

## Task 3: Add `RollingLogWriter`

**Files:**
- Create: `mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/RollingLogWriter.java`
- Create: `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/RollingLogWriterTest.java`

- [ ] **Step 1: Write the failing tests**

Create `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/RollingLogWriterTest.java`:

```java
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
    Clock clock = Clock.fixed(Instant.parse("2026-05-26T10:30:00Z"), ZoneOffset.UTC);
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
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
mvn test -pl mqtt_logger -Dtest=RollingLogWriterTest
```

Expected: compilation error — `RollingLogWriter` does not exist.

- [ ] **Step 3: Create `RollingLogWriter.java`**

Create `mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/RollingLogWriter.java`:

```java
package io.mapsmessaging.tools.valuelogger;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

public class RollingLogWriter implements LogWriter {

  private static final DateTimeFormatter HOUR_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd-HH").withZone(ZoneOffset.UTC);

  private final Path outputDir;
  private final OutputFormat format;
  private final long diskWarnBytes;
  private final Clock clock;
  private final LongSupplier diskSpaceSupplier;

  private LogWriter currentWriter;
  private String currentHourTag;

  public RollingLogWriter(Path outputDir, OutputFormat format, int diskWarnMb) {
    this(outputDir, format, diskWarnMb, Clock.systemUTC(),
        () -> outputDir.toFile().getFreeSpace());
  }

  RollingLogWriter(
      Path outputDir,
      OutputFormat format,
      int diskWarnMb,
      Clock clock,
      LongSupplier diskSpaceSupplier) {
    this.outputDir = outputDir;
    this.format = format;
    this.diskWarnBytes = (long) diskWarnMb * 1024 * 1024;
    this.clock = clock;
    this.diskSpaceSupplier = diskSpaceSupplier;
  }

  @Override
  public void open() throws Exception {
    Files.createDirectories(outputDir);
    currentHourTag = HOUR_FORMATTER.format(clock.instant());
    currentWriter = createWriter(currentHourTag);
    currentWriter.open();
  }

  @Override
  public synchronized void write(JsonObject logRecord) throws Exception {
    String hourTag = HOUR_FORMATTER.format(clock.instant());

    if (!hourTag.equals(currentHourTag)) {
      currentWriter.close();
      currentHourTag = hourTag;
      currentWriter = createWriter(currentHourTag);
      currentWriter.open();
      checkDiskSpace();
    }

    currentWriter.write(logRecord);
  }

  @Override
  public void close() throws IOException {
    if (currentWriter != null) {
      currentWriter.close();
    }
  }

  private LogWriter createWriter(String hourTag) {
    String extension = (format == OutputFormat.JSON) ? ".ndjson" : ".csv";
    String filePath = outputDir.resolve("maps-" + hourTag + extension).toString();

    if (format == OutputFormat.JSON) {
      return new NdjsonLogWriter(filePath);
    }

    return new CsvLogWriter(filePath);
  }

  private void checkDiskSpace() {
    long freeBytes = diskSpaceSupplier.getAsLong();

    if (freeBytes < diskWarnBytes) {
      long freeMb = freeBytes / (1024 * 1024);
      long thresholdMb = diskWarnBytes / (1024 * 1024);
      System.err.println("MAPS Logger WARNING: low disk space on " + outputDir
          + " — " + freeMb + " MB free (threshold " + thresholdMb + " MB)");
    }
  }
}
```

- [ ] **Step 4: Run tests — expect all to pass**

```bash
mvn test -pl mqtt_logger -Dtest=RollingLogWriterTest
```

Expected: `BUILD SUCCESS`, all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/RollingLogWriter.java \
        mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/RollingLogWriterTest.java
git commit -m "feat(mqtt_logger): add RollingLogWriter with hourly rotation and disk space monitor"
```

---

## Task 4: Update `LogWriterFactory` to create `RollingLogWriter`

**Files:**
- Modify: `mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/LogWriterFactory.java`
- Create: `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/LogWriterFactoryTest.java`

- [ ] **Step 1: Write the failing tests**

Create `mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/LogWriterFactoryTest.java`:

```java
package io.mapsmessaging.tools.valuelogger;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LogWriterFactoryTest {

  @Test
  void createsRollingLogWriterWhenOutputDirIsSet() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883", "--output-dir", "/tmp/logs"},
        key -> null);
    LogWriter writer = LogWriterFactory.create(args);
    assertInstanceOf(RollingLogWriter.class, writer);
  }

  @Test
  void createsCsvLogWriterForCsvOutputFile() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883",
                     "--topic", "/#", "--qos", "1", "--output", "out.csv"},
        key -> null);
    LogWriter writer = LogWriterFactory.create(args);
    assertInstanceOf(CsvLogWriter.class, writer);
  }

  @Test
  void createsNdjsonLogWriterForJsonOutputFile() {
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883",
                     "--topic", "/#", "--qos", "1", "--output", "out.ndjson"},
        key -> null);
    LogWriter writer = LogWriterFactory.create(args);
    assertInstanceOf(NdjsonLogWriter.class, writer);
  }

  @Test
  void defaultsToRollingLogWriter() {
    // No --output or --output-dir: defaults to rolling mode
    MapsValueLoggerArguments args = MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://localhost:1883"},
        key -> null);
    LogWriter writer = LogWriterFactory.create(args);
    assertInstanceOf(RollingLogWriter.class, writer);
  }
}
```

- [ ] **Step 2: Run tests — expect all 4 to fail**

```bash
mvn test -pl mqtt_logger -Dtest=LogWriterFactoryTest
```

Expected: all 4 tests fail because `LogWriterFactory.create()` still returns `CsvLogWriter` for the default case.

- [ ] **Step 3: Update `LogWriterFactory.java`**

Replace the entire file with:

```java
package io.mapsmessaging.tools.valuelogger;

import java.nio.file.Path;

public class LogWriterFactory {

  private LogWriterFactory() {
  }

  public static LogWriter create(MapsValueLoggerArguments arguments) {
    if (arguments.getOutputDir() != null) {
      return new RollingLogWriter(
          Path.of(arguments.getOutputDir()),
          arguments.getOutputFormat(),
          arguments.getDiskWarnMb());
    }

    if (arguments.getOutputFormat() == OutputFormat.JSON) {
      return new NdjsonLogWriter(arguments.getOutputFileName());
    }

    return new CsvLogWriter(arguments.getOutputFileName());
  }
}
```

- [ ] **Step 4: Run all module tests**

```bash
mvn test -pl mqtt_logger
```

Expected: `BUILD SUCCESS`. All tests from Tasks 2, 3, and 4 pass.

- [ ] **Step 5: Commit**

```bash
git add mqtt_logger/src/main/java/io/mapsmessaging/tools/valuelogger/LogWriterFactory.java \
        mqtt_logger/src/test/java/io/mapsmessaging/tools/valuelogger/LogWriterFactoryTest.java
git commit -m "feat(mqtt_logger): wire RollingLogWriter through LogWriterFactory"
```

---

## Task 5: Write systemd unit files and env template

**Files:**
- Create: `install/systemd/maps-logger.service`
- Create: `install/systemd/maps-logger-compress.service`
- Create: `install/systemd/maps-logger-compress.timer`
- Create: `install/systemd/maps-logger.env`

- [ ] **Step 1: Create `install/systemd/maps-logger.service`**

```ini
[Unit]
Description=MAPS Value Logger
After=network.target maps.service
Wants=maps.service

[Service]
Type=simple
EnvironmentFile=/etc/maps-logger/env
ExecStart=java -jar /usr/lib/maps-logger/maps-value-logger.jar
Restart=on-failure
RestartSec=10s
StandardOutput=journal
StandardError=journal
SyslogIdentifier=maps-logger

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 2: Create `install/systemd/maps-logger-compress.service`**

```ini
[Unit]
Description=Compress old MAPS Logger output files

[Service]
Type=oneshot
ExecStart=/bin/bash -c 'find /var/log/maps-logger -name "*.csv" -mmin +120 ! -name "*.gz" -exec gzip -f {} \;'
ExecStart=/bin/bash -c 'find /var/log/maps-logger -name "*.ndjson" -mmin +120 ! -name "*.gz" -exec gzip -f {} \;'
```

- [ ] **Step 3: Create `install/systemd/maps-logger-compress.timer`**

```ini
[Unit]
Description=Hourly compression of MAPS Logger files

[Timer]
OnCalendar=hourly
Persistent=true

[Install]
WantedBy=timers.target
```

- [ ] **Step 4: Create `install/systemd/maps-logger.env`**

```bash
# MAPS Value Logger configuration
# Edit this file, then run: systemctl start maps-logger
# To override individual values without editing this file: systemctl edit maps-logger

# Required: MQTT broker URL (must be localhost for Message-JSON transformation)
MAPS_URL=tcp://127.0.0.1:1883

# Topic filter to subscribe to
MAPS_TOPIC=/#

# MQTT QoS level: 0, 1, or 2
MAPS_QOS=1

# Output format: csv or json
MAPS_FORMAT=csv

# Directory for hourly output files (created automatically if absent)
MAPS_OUTPUT_DIR=/var/log/maps-logger

# Warn to system log when free space on the output filesystem falls below this many MB
MAPS_DISK_WARN_MB=500
```

- [ ] **Step 5: Commit**

```bash
git add install/systemd/
git commit -m "feat(install): add systemd unit files and env template"
```

---

## Task 6: Write `install.sh` and `maps-config-snippet.yaml`

**Files:**
- Create: `install/install.sh`
- Create: `install/maps-config-snippet.yaml`

- [ ] **Step 1: Create `install/maps-config-snippet.yaml`**

```yaml
# Add this transformation block to your MAPS server configuration YAML.
#
# It ensures that messages received on localhost TCP connections are
# delivered as JSON — the format the MAPS Value Logger expects.
#
# Place this at the top level of your MAPS server config file, or merge
# into an existing 'transformations' block.

transformations:
  data:
    - pattern: "*://*/*/*"
      transformation: ""

    - pattern: "tcp://127.0.0.1/*/*"
      transformation: "Message-JSON"
```

- [ ] **Step 2: Create `install/install.sh`**

```bash
#!/bin/bash
set -euo pipefail

INSTALL_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_DIR="/etc/systemd/system"
CONF_DIR="/etc/maps-logger"
LIB_DIR="/usr/lib/maps-logger"
LOG_DIR="/var/log/maps-logger"

# ---- Checks ----------------------------------------------------------------

if [[ $EUID -ne 0 ]]; then
  echo "Error: install.sh must be run as root (use sudo)" >&2
  exit 1
fi

check_java() {
  if ! command -v java &>/dev/null; then
    echo "Error: java not found. Install Java 21 or later and ensure it is on PATH." >&2
    exit 1
  fi

  local version
  version=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')

  if [[ "$version" -lt 21 ]] 2>/dev/null; then
    echo "Error: Java 21 or later required. Found Java $version." >&2
    exit 1
  fi
}

check_java

JAR_FILE=$(ls "$INSTALL_DIR"/mqtt_logger-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR_FILE" ]]; then
  echo "Error: no mqtt_logger-*.jar found in $INSTALL_DIR" >&2
  exit 1
fi

# ---- Install ---------------------------------------------------------------

echo "Creating directories..."
mkdir -p "$LIB_DIR" "$CONF_DIR" "$LOG_DIR"

echo "Installing JAR: $(basename "$JAR_FILE")"
cp "$JAR_FILE" "$LIB_DIR/maps-value-logger.jar"

echo "Installing systemd units..."
cp "$INSTALL_DIR/systemd/maps-logger.service"          "$SERVICE_DIR/"
cp "$INSTALL_DIR/systemd/maps-logger-compress.service" "$SERVICE_DIR/"
cp "$INSTALL_DIR/systemd/maps-logger-compress.timer"   "$SERVICE_DIR/"

echo "Installing environment file..."
if [[ ! -f "$CONF_DIR/env" ]]; then
  cp "$INSTALL_DIR/systemd/maps-logger.env" "$CONF_DIR/env"
  echo "  Created $CONF_DIR/env"
else
  echo "  $CONF_DIR/env already exists — not overwritten (existing config preserved)"
fi

echo "Enabling services..."
systemctl daemon-reload
systemctl enable maps-logger
systemctl enable maps-logger-compress.timer
systemctl start  maps-logger-compress.timer

echo ""
echo "Installation complete."
echo ""
echo "Next steps:"
echo "  1. Add the MAPS server transformation config from:"
echo "       $INSTALL_DIR/maps-config-snippet.yaml"
echo "  2. Edit $CONF_DIR/env to set MAPS_URL and other settings."
echo "  3. Start the logger: systemctl start maps-logger"
echo "  4. Check status:     systemctl status maps-logger"
echo "  5. View logs:        journalctl -u maps-logger -f"
```

- [ ] **Step 3: Make `install.sh` executable**

```bash
chmod +x install/install.sh
```

- [ ] **Step 4: Commit**

```bash
git add install/install.sh install/maps-config-snippet.yaml
git commit -m "feat(install): add install.sh and MAPS config snippet"
```

---

## Task 7: Write deployment scripts

**Files:**
- Create: `scripts/build.sh`
- Create: `scripts/bundle.sh`
- Create: `scripts/deploy.sh`

- [ ] **Step 1: Create `scripts/build.sh`**

```bash
#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn clean package -pl mqtt_logger
```

- [ ] **Step 2: Create `scripts/bundle.sh`**

```bash
#!/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "Building..."
"$REPO_ROOT/scripts/build.sh"

# Find the shaded JAR (mqtt_logger-<version>.jar in target/)
JAR=$(ls "$REPO_ROOT/mqtt_logger/target/mqtt_logger-"*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
  echo "Error: shaded JAR not found in mqtt_logger/target/" >&2
  exit 1
fi

BUNDLE_DIR="$REPO_ROOT/maps-logger-bundle"
TARBALL="$REPO_ROOT/maps-logger-install.tar.gz"

echo "Assembling bundle..."
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"

cp "$JAR" "$BUNDLE_DIR/"
cp -r "$REPO_ROOT/install/." "$BUNDLE_DIR/"

tar czf "$TARBALL" -C "$REPO_ROOT" "$(basename "$BUNDLE_DIR")/"
rm -rf "$BUNDLE_DIR"

echo "Bundle ready: $TARBALL"
```

- [ ] **Step 3: Create `scripts/deploy.sh`**

```bash
#!/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ $# -eq 0 ]]; then
  echo "Usage: deploy.sh <user@host> [<user@host>...]" >&2
  exit 1
fi

echo "Building and bundling..."
"$REPO_ROOT/scripts/bundle.sh"
TARBALL="$REPO_ROOT/maps-logger-install.tar.gz"

FAILED=0

for HOST in "$@"; do
  echo ""
  echo "==> Deploying to $HOST"

  if ! scp "$TARBALL" "$HOST:/tmp/maps-logger-install.tar.gz"; then
    echo "ERROR: failed to copy tarball to $HOST" >&2
    FAILED=$((FAILED + 1))
    continue
  fi

  if ! ssh "$HOST" \
      "cd /tmp && tar xzf maps-logger-install.tar.gz && sudo ./maps-logger-bundle/install.sh"; then
    echo "ERROR: install.sh failed on $HOST" >&2
    FAILED=$((FAILED + 1))
    continue
  fi

  echo "==> $HOST: OK"
done

echo ""
if [[ $FAILED -gt 0 ]]; then
  echo "$FAILED host(s) failed." >&2
  exit 1
fi

echo "All hosts deployed successfully."
```

- [ ] **Step 4: Make scripts executable**

```bash
chmod +x scripts/build.sh scripts/bundle.sh scripts/deploy.sh
```

- [ ] **Step 5: Verify bundle script produces a tarball**

```bash
./scripts/bundle.sh
ls -lh maps-logger-install.tar.gz
tar tzf maps-logger-install.tar.gz | head -20
```

Expected: tarball exists, contains `maps-logger-bundle/install.sh`, `maps-logger-bundle/systemd/maps-logger.service`, `maps-logger-bundle/mqtt_logger-1.0.0-SNAPSHOT.jar`, etc.

- [ ] **Step 6: Commit**

```bash
git add scripts/build.sh scripts/bundle.sh scripts/deploy.sh
git commit -m "feat(scripts): add build, bundle, and deploy scripts"
```

---

## Task 8: Write Docker files

**Files:**
- Create: `install/docker/Dockerfile`
- Create: `install/docker/docker-compose.yml`

- [ ] **Step 1: Run `bundle.sh` to stage the JAR into `install/docker/`**

`bundle.sh` already copies the JAR to the bundle directory, but the Dockerfile needs the JAR in its build context (`install/docker/`). Update `scripts/bundle.sh` to also copy the JAR there:

In `scripts/bundle.sh`, after the `cp "$JAR" "$BUNDLE_DIR/"` line, add:

```bash
# Stage JAR into Docker build context
cp "$JAR" "$REPO_ROOT/install/docker/$(basename "$JAR")"
```

So the relevant section becomes:

```bash
echo "Assembling bundle..."
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"

cp "$JAR" "$BUNDLE_DIR/"
cp "$JAR" "$REPO_ROOT/install/docker/$(basename "$JAR")"   # <-- add this line
cp -r "$REPO_ROOT/install/." "$BUNDLE_DIR/"
```

- [ ] **Step 2: Add `install/docker/` to `.gitignore` for staged JARs**

The staged JAR in `install/docker/` is a build artifact and must not be committed. Open `.gitignore` and add:

```
install/docker/*.jar
maps-logger-install.tar.gz
maps-logger-bundle/
```

- [ ] **Step 3: Create `install/docker/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre
COPY mqtt_logger-*.jar /app/maps-value-logger.jar
RUN mkdir -p /var/log/maps-logger
ENTRYPOINT ["java", "-jar", "/app/maps-value-logger.jar"]
```

- [ ] **Step 4: Create `install/docker/docker-compose.yml`**

```yaml
services:
  maps:
    image: mapsmessaging/maps-server:latest
    volumes:
      - ./maps-config:/opt/maps/conf
      - logger-data:/var/log/maps-logger
    ports:
      - "1883:1883"

  maps-logger:
    build:
      context: .
    network_mode: "service:maps"
    environment:
      MAPS_URL: tcp://127.0.0.1:1883
      MAPS_TOPIC: /#
      MAPS_QOS: 1
      MAPS_FORMAT: csv
      MAPS_OUTPUT_DIR: /var/log/maps-logger
      MAPS_DISK_WARN_MB: 500
    volumes:
      - logger-data:/var/log/maps-logger
    depends_on:
      - maps
    restart: on-failure

volumes:
  logger-data:
```

- [ ] **Step 5: Verify Docker build (requires Docker)**

Run from `install/docker/` after `scripts/bundle.sh` has staged the JAR:

```bash
cd install/docker
docker compose build
```

Expected: builds without error. Image is based on `eclipse-temurin:21-jre` and contains `/app/maps-value-logger.jar`.

- [ ] **Step 6: Commit**

```bash
git add .gitignore install/docker/Dockerfile install/docker/docker-compose.yml scripts/bundle.sh
git commit -m "feat(docker): add Dockerfile, docker-compose.yml, and JAR staging in bundle.sh"
```

---

## Self-Review Checklist (run before starting)

| Spec requirement | Task |
|---|---|
| `Main-Class` in manifest for `java -jar` | Task 1 |
| Env-var fallback with CLI priority | Task 2 |
| `--output-dir` / `MAPS_OUTPUT_DIR`, `--disk-warn-mb` | Task 2 |
| Defaults: topic `/#`, qos `1`, format `csv`, dir `/var/log/maps-logger`, warn `500` | Task 2 |
| Hourly-rolling dated files (`maps-YYYY-MM-DD-HH.csv`) | Task 3 |
| Disk space warning to stderr after each roll | Task 3 |
| No automatic deletion | Task 3 (no delete code) |
| `LogWriterFactory` routes to `RollingLogWriter` | Task 4 |
| `maps-logger.service` with `After=maps.service`, `EnvironmentFile=` | Task 5 |
| `maps-logger-compress.timer` (hourly gzip, no delete) | Task 5 |
| `maps-logger.env` template with all 6 vars | Task 5 |
| Idempotent `install.sh` (preserves existing env, checks Java 21+) | Task 6 |
| `maps-config-snippet.yaml` transformation block | Task 6 |
| `build.sh`, `bundle.sh`, `deploy.sh` (multi-host) | Task 7 |
| `Dockerfile` + `docker-compose.yml` with `network_mode: "service:maps"` | Task 8 |
| `MAPS_URL=tcp://127.0.0.1:1883` default in env template and compose | Tasks 5, 8 |
| JAR staged into Docker build context by `bundle.sh` | Task 8 |
| `.gitignore` for build artifacts | Task 8 |
