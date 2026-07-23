package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketInfo;
import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketReader;
import io.mapsmessaging.tools.mavlink.replay.ReplayFormat;
import io.mapsmessaging.tools.mavlink.replay.ReplayTimeline;
import io.mapsmessaging.tools.mavlink.replay.TimedReplayFrame;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Command(
    name = "maps-mavlink-replay",
    mixinStandardHelpOptions = true,
    description = "Replay MAVLink TLOG, MUDP, legacy DAT, or raw captures to UDP"
)
public class MavlinkReplayMain implements Callable<Integer> {

  @Option(names = {"-i", "--input"}, required = true, arity = "1..*", paramLabel = "FILE")
  private List<Path> inputPaths = new ArrayList<>();

  @Option(names = "--target-address", required = true, paramLabel = "ADDRESS")
  private String targetAddress;

  @Option(names = "--target-port", required = true, paramLabel = "PORT")
  private int targetPort;

  @Option(names = "--format", defaultValue = "auto", converter = ReplayFormatConverter.class)
  private ReplayFormat replayFormat;

  @Option(names = "--speed", defaultValue = "1.0", paramLabel = "FACTOR")
  private double speed;

  @Option(names = "--loop")
  private boolean loop;

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

  @Option(names = "--verbose")
  private boolean verbose;

  public static void main(String[] arguments) {
    System.exit(new CommandLine(new MavlinkReplayMain()).execute(arguments));
  }

  @Override
  public Integer call() throws Exception {
    validateArguments();

    InetAddress destinationAddress = InetAddress.getByName(targetAddress);
    MavlinkFilter mavlinkFilter = new MavlinkFilter(systemIds, componentIds, messageIds);
    MavlinkPacketReader mavlinkPacketReader = new MavlinkPacketReader();

    try (DatagramSocket datagramSocket = new DatagramSocket()) {
      do {
        long sent = replayOnce(datagramSocket, destinationAddress, mavlinkFilter, mavlinkPacketReader);
        if (sent == 0) {
          System.err.println("No matching MAVLink frames were replayed");
          return 1;
        }
      } while (loop);
    }

    return 0;
  }

  private long replayOnce(
      DatagramSocket datagramSocket,
      InetAddress destinationAddress,
      MavlinkFilter mavlinkFilter,
      MavlinkPacketReader mavlinkPacketReader
  ) throws Exception {
    long startNanos = startOffset.toNanos();
    long endNanos = endOffset == null ? Long.MAX_VALUE : endOffset.toNanos();
    long previousTimelineNanos = startNanos;
    long sent = 0;

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

        byte[] packetData = timedReplayFrame.replayFrame().packetData();
        MavlinkPacketInfo packetInfo = mavlinkFilter.isActive() || verbose ? mavlinkPacketReader.inspect(packetData) : null;
        if (mavlinkFilter.isActive() && !mavlinkFilter.matches(packetInfo)) {
          continue;
        }

        sleepScaled(Math.max(0, timelineNanos - previousTimelineNanos));
        DatagramPacket datagramPacket = new DatagramPacket(packetData, packetData.length, destinationAddress, targetPort);
        datagramSocket.send(datagramPacket);
        previousTimelineNanos = timelineNanos;
        sent++;

        if (verbose) {
          System.out.printf(
              "source=%s packet=%d timeMs=%d version=%d sysId=%d compId=%d msgId=%d bytes=%d%n",
              timedReplayFrame.sourcePath(),
              timedReplayFrame.replayFrame().packetNumber(),
              TimeUnit.NANOSECONDS.toMillis(timelineNanos),
              packetInfo.mavlinkVersion(),
              packetInfo.systemId(),
              packetInfo.componentId(),
              packetInfo.messageId(),
              packetData.length
          );
        }
      }
    }

    System.out.printf("Replayed %d MAVLink frames to %s:%d%n", sent, destinationAddress.getHostAddress(), targetPort);
    return sent;
  }

  private void sleepScaled(long delayNanos) throws InterruptedException {
    long scaledDelayNanos = Math.round(delayNanos / speed);
    if (scaledDelayNanos > 0) {
      TimeUnit.NANOSECONDS.sleep(scaledDelayNanos);
    }
  }

  private void validateArguments() {
    if (targetPort < 1 || targetPort > 65535) {
      throw new CommandLine.ParameterException(new CommandLine(this), "Target port must be between 1 and 65535");
    }
    if (!Double.isFinite(speed) || speed <= 0) {
      throw new CommandLine.ParameterException(new CommandLine(this), "Speed must be greater than zero");
    }
    if (endOffset != null && endOffset.compareTo(startOffset) < 0) {
      throw new CommandLine.ParameterException(new CommandLine(this), "End offset must not be before start offset");
    }
  }
}
