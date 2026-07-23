package io.mapsmessaging.tools.mavlink.replay;

import io.mapsmessaging.tools.mavlink.tlog.TlogRecord;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordReader;

import java.io.IOException;
import java.nio.file.Path;

public class TlogReplaySource implements ReplaySource {

  private final TlogRecordReader tlogRecordReader;
  private long packetNumber;
  private long previousTimestampNanos = -1;

  public TlogReplaySource(Path captureFile) throws IOException {
    this.tlogRecordReader = new TlogRecordReader(captureFile);
  }

  @Override
  public ReplayFrame nextFrame() throws IOException {
    TlogRecord tlogRecord = tlogRecordReader.read();
    if (tlogRecord == null) {
      return null;
    }

    long timestampNanos = Math.multiplyExact(tlogRecord.timestampMicros(), 1_000L);
    long delayNanos = previousTimestampNanos < 0 ? 0 : Math.max(0, timestampNanos - previousTimestampNanos);
    previousTimestampNanos = timestampNanos;
    return new ReplayFrame(++packetNumber, "tlog", delayNanos, timestampNanos, tlogRecord.packetData());
  }

  @Override
  public boolean usesSourceTimeline() {
    return true;
  }

  @Override
  public void close() throws IOException {
    tlogRecordReader.close();
  }
}
