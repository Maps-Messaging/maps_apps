package io.mapsmessaging.tools.valuelogger;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;

public class JsonLogRecordBuilder {

  public JsonObject build(String topic, String payload) {
    Instant receivedTimestamp = Instant.now();

    JsonObject source = JsonParser.parseString(payload).getAsJsonObject();

    JsonObject logRecord = new JsonObject();
    logRecord.addProperty("receivedTimestamp", receivedTimestamp.toString());
    logRecord.addProperty("topic", topic);

    addServerTime(source, logRecord, receivedTimestamp);

    addRootValue(source, logRecord, "contentType");
    addRootValue(source, logRecord, "identifier");
    addRootValue(source, logRecord, "qualityOfService", "qos");

    addMetaValue(source, logRecord, "protocol");
    addMetaValue(source, logRecord, "sessionId");
    addMetaValue(source, logRecord, "version", "metaVersion");

    return logRecord;
  }

  private void addServerTime(
      JsonObject source,
      JsonObject logRecord,
      Instant receivedTimestamp) {
    JsonElement timeElement = find(source, "meta", "time_ms");

    if (timeElement == null || timeElement.isJsonNull()) {
      logRecord.add("serverTimeMs", JsonNull.INSTANCE);
      logRecord.add("serverTimestamp", JsonNull.INSTANCE);
      logRecord.add("latencyMs", JsonNull.INSTANCE);
      return;
    }

    try {
      long serverTimeMs = Long.parseLong(timeElement.getAsString());
      Instant serverTimestamp = Instant.ofEpochMilli(serverTimeMs);
      long latencyMs = receivedTimestamp.toEpochMilli() - serverTimeMs;

      logRecord.addProperty("serverTimeMs", serverTimeMs);
      logRecord.addProperty("serverTimestamp", serverTimestamp.toString());
      logRecord.addProperty("latencyMs", latencyMs);
    } catch (NumberFormatException exception) {
      logRecord.add("serverTimeMs", timeElement);
      logRecord.add("serverTimestamp", JsonNull.INSTANCE);
      logRecord.add("latencyMs", JsonNull.INSTANCE);
    }
  }

  private void addRootValue(JsonObject source, JsonObject logRecord, String sourceName) {
    addRootValue(source, logRecord, sourceName, sourceName);
  }

  private void addRootValue(
      JsonObject source,
      JsonObject logRecord,
      String sourceName,
      String targetName) {
    JsonElement value = find(source, sourceName);
    addValue(logRecord, targetName, value);
  }

  private void addMetaValue(JsonObject source, JsonObject logRecord, String sourceName) {
    addMetaValue(source, logRecord, sourceName, sourceName);
  }

  private void addMetaValue(
      JsonObject source,
      JsonObject logRecord,
      String sourceName,
      String targetName) {
    JsonElement value = find(source, "meta", sourceName);
    addValue(logRecord, targetName, value);
  }

  private void addDataMapValue(JsonObject source, JsonObject logRecord, String sourceName) {
    addDataMapValue(source, logRecord, sourceName, sourceName);
  }

  private void addDataMapValue(
      JsonObject source,
      JsonObject logRecord,
      String sourceName,
      String targetName) {
    JsonElement value = find(source, "dataMap", sourceName, "data");
    addValue(logRecord, targetName, value);
  }

  private JsonElement find(JsonObject source, String... path) {
    JsonElement current = source;

    for (String pathElement : path) {
      if (current == null || !current.isJsonObject()) {
        return null;
      }

      JsonObject currentObject = current.getAsJsonObject();

      if (!currentObject.has(pathElement)) {
        return null;
      }

      current = currentObject.get(pathElement);
    }

    return current;
  }

  private void addValue(JsonObject logRecord, String name, JsonElement value) {
    if (value == null || value.isJsonNull()) {
      logRecord.add(name, JsonNull.INSTANCE);
      return;
    }

    logRecord.add(name, value);
  }
}