package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketInfo;
import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketReader;
import io.mapsmessaging.tools.mavlink.replay.ReplayFormat;
import io.mapsmessaging.tools.mavlink.replay.ReplayTimeline;
import io.mapsmessaging.tools.mavlink.replay.TimedReplayFrame;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Command(
    name = "maps-mavlink-inspect",
    mixinStandardHelpOptions = true,
    description = "Inspect MAVLink packet headers in capture files"
)
public class MavlinkInspectMain implements Callable<Integer> {

  @Option(names = {"-i", "--input"}, required = true, arity = "1..*", paramLabel = "FILE")
  private List<Path> inputPaths = new ArrayList<>();

  @Option(names = "--format", defaultValue = "auto", converter = ReplayFormatConverter.class)
  private ReplayFormat replayFormat;

  @Option(names = "--start-offset", defaultValue = "0", converter = TimeOffsetConverter.class, paramLabel = "OFFSET")
  private Duration startOffset;

  @Option(names = "--end-offset", converter = TimeOffsetConverter.class, paramLabel = "OFFSET")
  private Duration endOffset;

  @Option(names = "--raw-delay", defaultValue = "0ms", converter = TimeOffsetConverter.class, paramLabel = "OFFSET")
  private Duration rawDelay;

  @Option(names = "--system-id", split = ",", paramLabel = "ID")
  private Set<Integer> systemIds = new LinkedHashSet<>();

  @Option(names = "--component-id", split = ",", paramLabel = "ID")
  private Set<Integer> componentIds = new LinkedHashSet<>();

  @Option(names = "--message-id", split = ",", paramLabel = "ID")
  private Set<Integer> messageIds = new LinkedHashSet<>();

  @Option(names = "--limit", defaultValue = "0", paramLabel = "COUNT")
  private long limit;

  public static void main(String[] arguments) {
    System.exit(new CommandLine(new MavlinkInspectMain()).execute(arguments));
  }

  @Override
  public Integer call() throws Exception {
    validateArguments();

    long startNanos = startOffset.toNanos();
    long endNanos = endOffset == null ? Long.MAX_VALUE : endOffset.toNanos();
    long scanned = 0;
    long matched = 0;
    MavlinkFilter mavlinkFilter = new MavlinkFilter(systemIds, componentIds, messageIds);
    MavlinkPacketReader mavlinkPacketReader = new MavlinkPacketReader();

    try (ReplayTimeline replayTimeline = new ReplayTimeline(inputPaths, replayFormat, rawDelay.toNanos())) {
      TimedReplayFrame timedReplayFrame;
      while ((timedReplayFrame = replayTimeline.nextFrame()) != null) {
        long timelineNanos = timedReplayFrame.timelineNanos();
        if (timelineNanos < startNanos) {
          continue;
        }
        if (timelineNanos > endNanos) {
          break;
        }

        scanned++;
        MavlinkPacketInfo packetInfo = mavlinkPacketReader.inspect(timedReplayFrame.replayFrame().packetData());
        if (!mavlinkFilter.matches(packetInfo)) {
          continue;
        }

        matched++;
        System.out.printf(
            "source=%s packet=%d format=%s timeMs=%d version=%d sysId=%d compId=%d msgId=%d payload=%d sequence=%d signed=%s bytes=%d crc=0x%04X%n",
            timedReplayFrame.sourcePath(),
            timedReplayFrame.replayFrame().packetNumber(),
            timedReplayFrame.replayFrame().sourceFormat(),
            TimeUnit.NANOSECONDS.toMillis(timelineNanos),
            packetInfo.mavlinkVersion(),
            packetInfo.systemId(),
            packetInfo.componentId(),
            packetInfo.messageId(),
            packetInfo.payloadLength(),
            packetInfo.sequence(),
            packetInfo.signedPacket(),
            packetInfo.packetLength(),
            packetInfo.crc()
        );

        if (limit > 0 && matched >= limit) {
          break;
        }
      }
    }

    System.err.printf("Scanned %d frames, matched %d%n", scanned, matched);
    return matched == 0 ? 1 : 0;
  }

  private void validateArguments() {
    if (limit < 0) {
      throw new CommandLine.ParameterException(new CommandLine(this), "Limit must not be negative");
    }
    if (endOffset != null && endOffset.compareTo(startOffset) < 0) {
      throw new CommandLine.ParameterException(new CommandLine(this), "End offset must not be before start offset");
    }
  }
}
