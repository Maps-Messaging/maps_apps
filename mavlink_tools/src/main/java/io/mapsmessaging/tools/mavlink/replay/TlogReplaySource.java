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
    long timestampNanos;
    try {
      timestampNanos = Math.multiplyExact(readTimestampMicros(), 1_000L);
    } catch (EOFException exception) {
      return null;
    }

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

  private long readTimestampMicros() throws IOException {
    byte[] timestampBuffer = inputStream.readNBytes(Long.BYTES);
    if (timestampBuffer.length == 0) {
      throw new EOFException("End of tlog");
    }
    if (timestampBuffer.length != Long.BYTES) {
      throw new EOFException("Truncated tlog timestamp");
    }

    return ((long) timestampBuffer[0] & 0xFF) << 56
        | ((long) timestampBuffer[1] & 0xFF) << 48
        | ((long) timestampBuffer[2] & 0xFF) << 40
        | ((long) timestampBuffer[3] & 0xFF) << 32
        | ((long) timestampBuffer[4] & 0xFF) << 24
        | ((long) timestampBuffer[5] & 0xFF) << 16
        | ((long) timestampBuffer[6] & 0xFF) << 8
        | ((long) timestampBuffer[7] & 0xFF);
  }
}
