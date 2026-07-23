package io.mapsmessaging.tools.mavlink.tlog;

import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketReader;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class TlogRecordReader implements Closeable {

  private final InputStream inputStream;
  private final MavlinkPacketReader mavlinkPacketReader;

  public TlogRecordReader(Path inputPath) throws IOException {
    this.inputStream = new BufferedInputStream(Files.newInputStream(inputPath));
    this.mavlinkPacketReader = new MavlinkPacketReader();
  }

  public TlogRecord read() throws IOException {
    Long timestampMicros = readTimestampMicros();
    if (timestampMicros == null) {
      return null;
    }

    byte[] packetData = mavlinkPacketReader.readPacket(inputStream);
    if (packetData == null) {
      throw new EOFException("TLOG ended after a timestamp without a MAVLink frame");
    }

    return new TlogRecord(timestampMicros, packetData);
  }

  private Long readTimestampMicros() throws IOException {
    int firstByte = inputStream.read();
    if (firstByte < 0) {
      return null;
    }

    byte[] remainingBytes = inputStream.readNBytes(Long.BYTES - 1);
    if (remainingBytes.length != Long.BYTES - 1) {
      throw new EOFException("Truncated TLOG timestamp");
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

  @Override
  public void close() throws IOException {
    inputStream.close();
  }
}
