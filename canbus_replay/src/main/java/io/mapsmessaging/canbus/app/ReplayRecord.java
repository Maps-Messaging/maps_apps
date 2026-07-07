package io.mapsmessaging.canbus.app;

import io.mapsmessaging.canbus.device.frames.CanFrame;


import java.time.Instant;
import java.util.Objects;

public record ReplayRecord(Instant timestamp, int canId, boolean extended, int dlc, byte[] data) {

  public ReplayRecord {
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(data, "data");

    if (dlc < 0 || dlc > 64) {
      throw new IllegalArgumentException("Invalid DLC: " + dlc);
    }

    if (data.length < dlc) {
      throw new IllegalArgumentException("Payload data length " + data.length + " is shorter than DLC " + dlc);
    }
  }

  public CanFrame toCanFrame() {
    byte[] frameData = data.length == dlc ? data : copyFrameData(data, dlc);
    return new CanFrame(canId, extended, dlc, frameData);
  }

  private static byte[] copyFrameData(byte[] data, int dlc) {
    byte[] copy = new byte[dlc];
    System.arraycopy(data, 0, copy, 0, dlc);
    return copy;
  }
}
