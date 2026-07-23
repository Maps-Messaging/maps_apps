package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.mavlink.MavlinkMessageFormatLoader;
import io.mapsmessaging.mavlink.codec.MavlinkCodec;
import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketInfo;
import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketReader;
import io.mapsmessaging.tools.mavlink.tlog.MavlinkSystemIdPacketRewriter;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecord;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordReader;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

@Command(
    name = "maps-mavlink-tlog-system-id",
    mixinStandardHelpOptions = true,
    description = "Rewrite one MAVLink system ID in a TLOG and recalculate frame checksums"
)
public class TlogSystemIdRewriter implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "INPUT.tlog")
  private Path inputPath;

  @Parameters(index = "1", paramLabel = "OUTPUT.tlog")
  private Path outputPath;

  @Parameters(index = "2", paramLabel = "OLD_SYSTEM_ID")
  private int oldSystemId;

  @Parameters(index = "3", paramLabel = "NEW_SYSTEM_ID")
  private int newSystemId;

  @Option(names = "--start-offset", defaultValue = "0", converter = TimeOffsetConverter.class, paramLabel = "OFFSET")
  private Duration startOffset;

  @Option(names = "--end-offset", converter = TimeOffsetConverter.class, paramLabel = "OFFSET")
  private Duration endOffset;

  @Option(names = "--dialect-file", paramLabel = "FILE")
  private Path dialectFile;

  public static void main(String[] arguments) {
    System.exit(new CommandLine(new TlogSystemIdRewriter()).execute(arguments));
  }

  @Override
  public Integer call() throws Exception {
    validateArguments();

    MavlinkMessageFormatLoader loader = MavlinkMessageFormatLoader.getInstance();
    MavlinkCodec mavlinkCodec = dialectFile == null
        ? loader.getDialectOrThrow("common")
        : loader.loadDialect(dialectFile);
    MavlinkSystemIdPacketRewriter packetRewriter = new MavlinkSystemIdPacketRewriter(mavlinkCodec.getRegistry());
    MavlinkPacketReader packetReader = new MavlinkPacketReader();

    long firstTimestampMicros = -1;
    long startOffsetMicros = startOffset.toNanos() / 1_000L;
    long endOffsetMicros = endOffset == null ? Long.MAX_VALUE : endOffset.toNanos() / 1_000L;
    long totalRecords = 0;
    long rewrittenRecords = 0;

    try (
        TlogRecordReader tlogRecordReader = new TlogRecordReader(inputPath);
        TlogRecordWriter tlogRecordWriter = new TlogRecordWriter(outputPath)
    ) {
      TlogRecord tlogRecord;
      while ((tlogRecord = tlogRecordReader.read()) != null) {
        if (firstTimestampMicros < 0) {
          firstTimestampMicros = tlogRecord.timestampMicros();
        }

        totalRecords++;
        long offsetMicros = Math.max(0, tlogRecord.timestampMicros() - firstTimestampMicros);
        byte[] packetData = tlogRecord.packetData();

        if (offsetMicros >= startOffsetMicros && offsetMicros <= endOffsetMicros) {
          MavlinkPacketInfo packetInfo = packetReader.inspect(packetData);
          if (packetInfo.systemId() == oldSystemId) {
            packetData = packetRewriter.rewrite(packetData, oldSystemId, newSystemId);
            rewrittenRecords++;
          }
        }

        tlogRecordWriter.write(new TlogRecord(tlogRecord.timestampMicros(), packetData));
      }
    }

    System.out.printf(
        "Rewrote system ID %d to %d in %d of %d TLOG records: %s%n",
        oldSystemId,
        newSystemId,
        rewrittenRecords,
        totalRecords,
        outputPath.toAbsolutePath()
    );
    return rewrittenRecords == 0 ? 1 : 0;
  }

  private void validateArguments() {
    validateSystemId(oldSystemId, "Old system ID");
    validateSystemId(newSystemId, "New system ID");
    if (oldSystemId == newSystemId) {
      throw new CommandLine.ParameterException(new CommandLine(this), "Old and new system IDs must differ");
    }
    if (endOffset != null && endOffset.compareTo(startOffset) < 0) {
      throw new CommandLine.ParameterException(new CommandLine(this), "End offset must not be before start offset");
    }
    if (inputPath.toAbsolutePath().normalize().equals(outputPath.toAbsolutePath().normalize())) {
      throw new CommandLine.ParameterException(new CommandLine(this), "Output file must not replace the input file");
    }
  }

  private void validateSystemId(int systemId, String name) {
    if (systemId < 0 || systemId > 255) {
      throw new CommandLine.ParameterException(new CommandLine(this), name + " must be between 0 and 255");
    }
  }
}
