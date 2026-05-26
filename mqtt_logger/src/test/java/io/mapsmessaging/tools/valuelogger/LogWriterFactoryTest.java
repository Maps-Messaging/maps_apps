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
