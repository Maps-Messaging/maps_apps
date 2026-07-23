package io.mapsmessaging.tools.mavlink.replay;

public record ReplayFrame(
    long packetNumber,
    String sourceFormat,
    long delayNanos,
    long sourceTimestampNanos,
    byte[] packetData
) {
}
