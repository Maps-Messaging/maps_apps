package io.mapsmessaging.tools.mavlink.replay;

import io.mapsmessaging.tools.udphelpers.common.UdpPacketRecord;
import io.mapsmessaging.tools.udphelpers.replay.UdpPacketRecordReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class MudpReplaySource implements ReplaySource {

  private final InputStream inputStream;
  private final UdpPacketRecordReader udpPacketRecordReader;
  private long packetNumber;
  private long previousTimestampNanos = -1;

  public MudpReplaySource(Path captureFile) throws IOException {
    this.inputStream = Files.newInputStream(captureFile);
    this.udpPacketRecordReader = new UdpPacketRecordReader(inputStream);
  }

  @Override
  public ReplayFrame nextFrame() throws IOException {
    Optional<UdpPacketRecord> recordOptional = udpPacketRecordReader.read();
    if (recordOptional.isEmpty()) {
      return null;
    }

    UdpPacketRecord record = recordOptional.get();
    long delayNanos = previousTimestampNanos < 0 ? 0 : Math.max(0, record.getTimestampNanos() - previousTimestampNanos);
    previousTimestampNanos = record.getTimestampNanos();
    return new ReplayFrame(++packetNumber, "mudp", delayNanos, record.getTimestampNanos(), record.getPayload());
  }

  @Override
  public void close() throws IOException {
    udpPacketRecordReader.close();
  }
}
