package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.tlog.TlogRecord;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordReader;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

@Command(
    name = "maps-mavlink-tlog-tail",
    mixinStandardHelpOptions = true,
    description = "Extract the final duration from a MAVLink TLOG without modifying packets or timestamps"
)
public class TlogTailExtractor implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "INPUT.tlog")
  private Path inputPath;

  @Parameters(index = "1", paramLabel = "OUTPUT.tlog")
  private Path outputPath;

  @Parameters(index = "2", paramLabel = "DURATION", converter = TimeOffsetConverter.class)
  private Duration duration;

  public static void main(String[] arguments) {
    System.exit(new CommandLine(new TlogTailExtractor()).execute(arguments));
  }

  @Override
  public Integer call() throws Exception {
    validateArguments();

    long firstTimestampMicros = -1;
    long lastTimestampMicros = -1;
    long totalRecords = 0;

    try (TlogRecordReader tlogRecordReader = new TlogRecordReader(inputPath)) {
      TlogRecord tlogRecord;
      while ((tlogRecord = tlogRecordReader.read()) != null) {
        if (firstTimestampMicros < 0) {
          firstTimestampMicros = tlogRecord.timestampMicros();
        }
        lastTimestampMicros = Math.max(lastTimestampMicros, tlogRecord.timestampMicros());
        totalRecords++;
      }
    }

    if (totalRecords == 0) {
      System.err.println("Input TLOG contains no MAVLink records");
      return 1;
    }

    long durationMicros = duration.toNanos() / 1_000L;
    long startTimestampMicros = Math.max(firstTimestampMicros, lastTimestampMicros - durationMicros);
    long writtenRecords = 0;

    try (
        TlogRecordReader tlogRecordReader = new TlogRecordReader(inputPath);
        TlogRecordWriter tlogRecordWriter = new TlogRecordWriter(outputPath)
    ) {
      TlogRecord tlogRecord;
      while ((tlogRecord = tlogRecordReader.read()) != null) {
        if (tlogRecord.timestampMicros() >= startTimestampMicros) {
          tlogRecordWriter.write(tlogRecord);
          writtenRecords++;
        }
      }
    }

    System.out.printf(
        "Extracted %d of %d TLOG records from %s to %s%n",
        writtenRecords,
        totalRecords,
        inputPath.toAbsolutePath(),
        outputPath.toAbsolutePath()
    );
    return 0;
  }

  private void validateArguments() {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new CommandLine.ParameterException(new CommandLine(this), "Duration must be greater than zero");
    }
    if (inputPath.toAbsolutePath().normalize().equals(outputPath.toAbsolutePath().normalize())) {
      throw new CommandLine.ParameterException(new CommandLine(this), "Output file must not replace the input file");
    }
  }
}
