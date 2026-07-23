package io.mapsmessaging.tools.mavlink.tlog;

public record TlogRecord(long timestampMicros, byte[] packetData) {
}
