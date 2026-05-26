package io.mapsmessaging.tools.valuelogger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class CsvLogWriter implements LogWriter {

  private static final String[] HEADERS = {
      "receivedTimestamp",
      "serverTimestamp",
      "serverTimeMs",
      "latencyMs",
      "topic",
      "contentType",
      "identifier",
      "qos",
      "protocol",
      "sessionId",
      "metaVersion"
  };

  private final String outputFileName;

  private BufferedWriter writer;
  private boolean closeWriter;

  public CsvLogWriter(String outputFileName) {
    this.outputFileName = outputFileName;
  }

  @Override
  public void open() throws Exception {
    if (outputFileName == null || outputFileName.isBlank() || "-".equals(outputFileName)) {
      writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
      closeWriter = false;
      writeHeader();
      return;
    }

    Path outputPath = Path.of(outputFileName);
    boolean writeHeader = !Files.exists(outputPath) || Files.size(outputPath) == 0;

    writer = Files.newBufferedWriter(
        outputPath,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);

    closeWriter = true;

    if (writeHeader) {
      writeHeader();
    }
  }

  @Override
  public synchronized void write(JsonObject logRecord) throws Exception {
    for (int headerIndex = 0; headerIndex < HEADERS.length; headerIndex++) {
      if (headerIndex > 0) {
        writer.write(',');
      }

      String header = HEADERS[headerIndex];
      JsonElement value = logRecord.get(header);
      writer.write(escape(value));
    }

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

  private void writeHeader() throws Exception {
    for (int headerIndex = 0; headerIndex < HEADERS.length; headerIndex++) {
      if (headerIndex > 0) {
        writer.write(',');
      }

      writer.write(escape(HEADERS[headerIndex]));
    }

    writer.newLine();
    writer.flush();
  }

  private String escape(JsonElement value) {
    if (value == null || value.isJsonNull()) {
      return "";
    }

    if (value.isJsonPrimitive()) {
      return escape(value.getAsString());
    }

    return escape(value.toString());
  }

  private String escape(String value) {
    if (value == null) {
      return "";
    }

    boolean mustQuote =
        value.contains(",")
            || value.contains("\"")
            || value.contains("\n")
            || value.contains("\r");

    String escapedValue = value.replace("\"", "\"\"");

    if (mustQuote) {
      return "\"" + escapedValue + "\"";
    }

    return escapedValue;
  }
}