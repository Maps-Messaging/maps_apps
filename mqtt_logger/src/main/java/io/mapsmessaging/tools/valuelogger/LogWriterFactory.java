package io.mapsmessaging.tools.valuelogger;

public class LogWriterFactory {

  private LogWriterFactory() {
  }

  public static LogWriter create(MapsValueLoggerArguments arguments) {
    if (arguments.getOutputFormat() == OutputFormat.JSON) {
      return new NdjsonLogWriter(arguments.getOutputFileName());
    }

    return new CsvLogWriter(arguments.getOutputFileName());
  }
}