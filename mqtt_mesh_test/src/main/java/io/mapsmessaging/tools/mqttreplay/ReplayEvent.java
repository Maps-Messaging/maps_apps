package io.mapsmessaging.tools.mqttreplay;

import java.time.Instant;

public record ReplayEvent(Instant timestamp, String topic, byte[] payload, int qos, boolean retained) {
}
