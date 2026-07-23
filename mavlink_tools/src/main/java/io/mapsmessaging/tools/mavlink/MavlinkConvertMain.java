package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketInfo;
import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketReader;
import io.mapsmessaging.tools.mavlink.replay.ReplayFormat;
import io.mapsmessaging.tools.mavlink.replay.ReplayTimeline;
import io.mapsmessaging.tools.mavlink.replay.TimedReplayFrame;
import io.mapsmessaging.tools.udphelpers.capture.UdpPacketRecordWriter;
import io.mapsmessaging.tools.udphelpers.common.UdpPacketRecord;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
    name = "maps-mavlink-convert",
    mixinStandardHelpOptions = true,
    description = "Convert MAVLink captures into the Maps MUDP capture format"
)
public class MavlinkConvertMain implements Callable<Integer> {

  @Option(names = {"-i", "--input"}, required = true, arity = "1..*", paramLabel = "FILE")
  private List<Path> inputPaths = new ArrayList<>();

  @Option(names = {"-o", "--output"}, required = true, paramLabel = "FILE")
  private Path outputPath;

  @Option(names = "--format", defaultValue = "auto", converter = ReplayFormatConverter.class)
  private ReplayFormat replayFormat;

  @Option(names = "--start-offset", defaultValue = "0", converter = TimeOffsetConverter.class, paramLabel = "OFFSET")
  private Duration startOffset;

  @Option(names = "--end-offset", converter = TimeOffsetConverter.class, paramLabel = "OFFSET")
  private Duration endOffset;

  @Option(names = "--raw-delay", defaultValue = "0ms", converter = TimeOffsetConverter.class, paramLabel = "OFFSET")
  private Duration rawDelay;

  @Option(names = "--source-address", defaultValue = "127.0.0.1", paramLabel = "ADDRESS")
  private String sourceAddress;

  @Option(names = "--source-port", defaultValue = "0", paramLabel = "PORT")
  private int sourcePort;

  @Option(names = "--flush-each-packet", negatable = true, defaultValue = "true")
  private boolean flushEachPacket;

  @Option(names = "--system-id", split = ",", paramLabel = "ID")
  private Set<Integer> systemIds = new LinkedHashSet<>();

  @Option(names = "--component-id", split = ",", paramLabel = "ID")
  private Set<Integer> componentIds = new LinkedHashSet<>();

  @Option(names = "--message-id", split = ",", paramLabel = "ID")
  private Set<Integer> messageIds = new LinkedHashSet<>();

  public static void main(String[] arguments) {
    System.exit(new CommandLine(new MavlinkConvertMain()).execute(arguments));
  }

  @Override
  public Integer call() throws Exception {
    validateArguments();

    Path absoluteOutputPath = outputPath.toAbsolutePath();
    Path parentPath = absoluteOutputPath.getParent();
    if (parentPath != null) {
      Files.createDirectories(parentPath);
    }

    InetAddress recordSourceAddress = InetAddress.getByName(sourceAddress);
    MavlinkFilter mavlinkFilter = new MavlinkFilter(systemIds, componentIds, messageIds);
    MavlinkPacketReader mavlinkPacketReader = new MavlinkPacketReader();
    long startNanos = startOffset.toNanos();
    long endNanos = endOffset == null ? Long.MAX_VALUE : endOffset.toNanos();
    long outputOriginNanos = -1;
    long written = 0;

    StandardOpenOption[] openOptions = new StandardOpenOption[]{
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
    };

    try (
        ReplayTimeline replayTimeline = new ReplayTimeline(inputPaths, replayFormat, rawDelay.toNanos());
        OutputStream outputStream = Files.newOutputStream(absoluteOutputPath, openOptions);
        UdpPacketRecordWriter udpPacketRecordWriter = new UdpPacketRecordWriter(outputStream, flushEachPacket)
    ) {
      TimedReplayFrame timedReplayFrame;
      while ((timedReplayFrame = replayTimeline.nextFrame()) != null) {
        long timelineNanos = timedReplayFrame.timelineNanos();
        if (timelineNanos < startNanos) {
          continue;
        }
        if (timelineNanos > endNanos) {
          break;
        }

        byte[] packetData = timedReplayFrame.replayFrame().packetData();
        if (mavlinkFilter.isActive()) {
          MavlinkPacketInfo packetInfo = mavlinkPacketReader.inspect(packetData);
          if (!mavlinkFilter.matches(packetInfo)) {
            continue;
          }
        }

        if (outputOriginNanos < 0) {
          outputOriginNanos = timelineNanos;
        }
        udpPacketRecordWriter.write(new UdpPacketRecord(
            timelineNanos - outputOriginNanos,
            recordSourceAddress,
            sourcePort,
            packetData
        ));
        written++;
      }
    }

    System.out.printf("Converted %d MAVLink frames to %s%n", written, absoluteOutputPath);
    return written == 0 ? 1 : 0;
  }

  private void validateArguments() {
    if (sourcePort < 0 || sourcePort > 65535) {
      throw new CommandLine.ParameterException(new CommandLine(this), "Source port must be between 0 and 65535");
    }
    if (endOffset != null && endOffset.compareTo(startOffset) < 0) {
      throw new CommandLine.ParameterException(new CommandLine(this), "End offset must not be before start offset");
    }
    for (Path inputPath : inputPaths) {
      if (inputPath.toAbsolutePath().equals(outputPath.toAbsolutePath())) {
        throw new CommandLine.ParameterException(new CommandLine(this), "Output file must not replace an input file");
      }
    }
  }
}
