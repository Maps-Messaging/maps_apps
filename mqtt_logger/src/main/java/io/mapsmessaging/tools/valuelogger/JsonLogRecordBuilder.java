package io.mapsmessaging.tools.valuelogger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;

public class JsonLogRecordBuilder {

  private final Gson gson;

  public JsonLogRecordBuilder() {
    this.gson = new Gson();
  }

  public String build(String topic, String payload) {
    JsonObject source = JsonParser.parseString(payload).getAsJsonObject();

    JsonObject logRecord = new JsonObject();
    logRecord.addProperty("timestamp", Instant.now().toString());
    logRecord.addProperty("topic", topic);

    addLoggedValues(source, logRecord);

    return gson.toJson(logRecord);
  }

  private void addLoggedValues(JsonObject source, JsonObject logRecord) {
    /*
     * Placeholder.
     *
     * Once the actual MAPS JSON body is known, hardwire the fields here.
     *
     * Example:
     *
     * JsonElement battery = find(source, "payload", "battery", "percentage");
     * addIfPresent(logRecord, "battery", battery);
     */
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

  private void addIfPresent(JsonObject logRecord, String name, JsonElement value) {
    if (value == null || value.isJsonNull()) {
      logRecord.add(name, null);
      return;
    }

    logRecord.add(name, value);
  }
}