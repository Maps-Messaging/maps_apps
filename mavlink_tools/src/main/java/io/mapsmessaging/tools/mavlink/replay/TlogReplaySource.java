package io.mapsmessaging.tools.mavlink.replay;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class TlogReplaySource implements ReplaySource {

  private final InputStream inputStream;
  private final MavlinkPacketReader mavlinkPacketReader;
  private long packetNumber;
  private long previousTimestampNanos = -1;

  public TlogReplaySource(Path captureFile) throws IOException {
    this.inputStream = new BufferedInputStream(Files.newInputStream(captureFile));
    this.mavlinkPacketReader = new MavlinkPacketReader();
  }

  @Override
  public ReplayFrame nextFrame() throws IOException {
    Long timestampMicros = readTimestampMicros();
    if (timestampMicros == null) {
      return null;
    }

    long timestampNanos = Math.multiplyExact(timestampMicros, 1_000L);
    byte[] packetData = mavlinkPacketReader.readPacket(inputStream);
    if (packetData == null) {
      throw new EOFException("TLOG ended after a timestamp without a MAVLink frame");
    }

    long delayNanos = previousTimestampNanos < 0 ? 0 : Math.max(0, timestampNanos - previousTimestampNanos);
    previousTimestampNanos = timestampNanos;
    return new ReplayFrame(++packetNumber, "tlog", delayNanos, timestampNanos, packetData);
  }

  @Override
  public boolean usesSourceTimeline() {
    return true;
  }

  @Override
  public void close() throws IOException {
    inputStream.close();
  }

  private Long readTimestampMicros() throws IOException {
    int firstByte = inputStream.read();
    if (firstByte < 0) {
      return null;
    }

    byte[] remainingBytes = inputStream.readNBytes(Long.BYTES - 1);
    if (remainingBytes.length != Long.BYTES - 1) {
      throw new EOFException("Truncated tlog timestamp");
    }

    return ((long) firstByte & 0xFF) << 56
        | ((long) remainingBytes[0] & 0xFF) << 48
        | ((long) remainingBytes[1] & 0xFF) << 40
        | ((long) remainingBytes[2] & 0xFF) << 32
        | ((long) remainingBytes[3] & 0xFF) << 24
        | ((long) remainingBytes[4] & 0xFF) << 16
        | ((long) remainingBytes[5] & 0xFF) << 8
        | ((long) remainingBytes[6] & 0xFF);
  }
}
