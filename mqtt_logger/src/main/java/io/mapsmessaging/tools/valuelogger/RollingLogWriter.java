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
