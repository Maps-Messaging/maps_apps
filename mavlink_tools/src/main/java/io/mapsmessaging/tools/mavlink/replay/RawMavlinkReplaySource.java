package io.mapsmessaging.tools.mavlink.replay;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class RawMavlinkReplaySource implements ReplaySource {

  private final InputStream inputStream;
  private final MavlinkPacketReader mavlinkPacketReader;
  private final long delayNanos;
  private long packetNumber;

  public RawMavlinkReplaySource(Path captureFile, long delayNanos) throws IOException {
    this.inputStream = new BufferedInputStream(Files.newInputStream(captureFile));
    this.mavlinkPacketReader = new MavlinkPacketReader();
    this.delayNanos = Math.max(0, delayNanos);
  }

  @Override
  public ReplayFrame nextFrame() throws IOException {
    byte[] packetData = mavlinkPacketReader.readPacket(inputStream);
    if (packetData == null) {
      return null;
    }
    return new ReplayFrame(++packetNumber, "raw", packetNumber == 1 ? 0 : delayNanos, -1, packetData);
  }

  @Override
  public void close() throws IOException {
    inputStream.close();
  }
}
