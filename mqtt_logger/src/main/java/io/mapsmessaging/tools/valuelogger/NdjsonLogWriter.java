package io.mapsmessaging.tools.valuelogger;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class NdjsonLogWriter implements Closeable {

  private final String outputFileName;

  private BufferedWriter writer;
  private boolean closeWriter;

  public NdjsonLogWriter(String outputFileName) {
    this.outputFileName = outputFileName;
  }

  public void open() throws Exception {
    if (outputFileName == null || outputFileName.isBlank() || "-".equals(outputFileName)) {
      writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
      closeWriter = false;
      return;
    }

    Path outputPath = Path.of(outputFileName);

    writer = Files.newBufferedWriter(
        outputPath,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);

    closeWriter = true;
  }

  public synchronized void write(String logRecord) throws Exception {
    writer.write(logRecord);
    writer.newLine();
    writer.flush();
  }

  @Override
  public void close() throws java.io.IOException {
    if (writer == null) {
      return;
    }

    writer.flush();

    if (closeWriter) {
      writer.close();
    }
  }
}