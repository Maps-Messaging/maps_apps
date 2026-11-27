package io.mapsmessaging.tools.mqttreplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

final class NdjsonReplayDecoder {
  private NdjsonReplayDecoder() {
  }

  static ReplayEvent decode(String line, Integer qosOverride) {
    JsonObject record = JsonParser.parseString(line).getAsJsonObject();
    if (!present(record, "topic") || record.get("topic").getAsString().isBlank()) {
      throw new IllegalArgumentException("Missing topic");
    }
    String topic = record.get("topic").getAsString();
    if (topic.indexOf('+') >= 0 || topic.indexOf('#') >= 0) {
      throw new IllegalArgumentException("Invalid MQTT PUBLISH topic (wildcards are not allowed): " + topic);
    }
    Instant timestamp;
    if (present(record, "receivedTimestamp")) {
      timestamp = Instant.parse(record.get("receivedTimestamp").getAsString());
    } else if (present(record, "serverTimestamp")) {
      timestamp = Instant.parse(record.get("serverTimestamp").getAsString());
    } else if (present(record, "serverTimeMs")) {
      timestamp = Instant.ofEpochMilli(record.get("serverTimeMs").getAsLong());
    } else if (record.has("meta") && record.get("meta").isJsonObject()
        && present(record.getAsJsonObject("meta"), "time_ms")) {
      timestamp = Instant.ofEpochMilli(record.getAsJsonObject("meta").get("time_ms").getAsLong());
    } else if (present(record, "timestamp")) {
      timestamp = Instant.parse(record.get("timestamp").getAsString());
    } else {
      throw new IllegalArgumentException("Missing timestamp");
    }

    byte[] bytes;
    if (present(record, "opaqueData")) {
      bytes = Base64.getDecoder().decode(record.get("opaqueData").getAsString());
    } else {
      if (!present(record, "payload")) {
        throw new IllegalArgumentException("Missing payload");
      }
      JsonElement payload = record.get("payload");
      bytes = (payload.isJsonPrimitive() && payload.getAsJsonPrimitive().isString()
          ? payload.getAsString() : payload.toString()).getBytes(StandardCharsets.UTF_8);
    }

    int qos = 0;
    if (present(record, "qualityOfService") || present(record, "qos")) {
      String value = record.get(present(record, "qualityOfService") ? "qualityOfService" : "qos").getAsString();
      qos = switch (value) {
        case "AT_MOST_ONCE" -> 0;
        case "AT_LEAST_ONCE" -> 1;
        case "EXACTLY_ONCE" -> 2;
        default -> Integer.parseInt(value);
      };
    }
    if (qos < 0 || qos > 2) {
      throw new IllegalArgumentException("Invalid QoS");
    }
    if (qosOverride != null) {
      if (qosOverride < 0 || qosOverride > 2) {
        throw new IllegalArgumentException("Invalid QoS");
      }
      qos = qosOverride;
    }
    boolean retained = present(record, "retain") ? record.get("retain").getAsBoolean()
        : present(record, "retained") && record.get("retained").getAsBoolean();
    return new ReplayEvent(timestamp, topic, bytes, qos, retained);
  }

  private static boolean present(JsonObject record, String field) {
    return record.has(field) && !record.get(field).isJsonNull();
  }
}
