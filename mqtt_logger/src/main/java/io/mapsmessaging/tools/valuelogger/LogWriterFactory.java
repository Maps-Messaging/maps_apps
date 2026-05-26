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