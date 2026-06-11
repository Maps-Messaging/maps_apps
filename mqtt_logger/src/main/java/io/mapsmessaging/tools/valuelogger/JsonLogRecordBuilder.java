package io.mapsmessaging.tools.valuelogger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;

public class JsonLogRecordBuilder {

  public JsonObject build(String topic, String payload) {
    Instant receivedTimestamp = Instant.now();
    JsonObject source = JsonParser.parseString(payload).getAsJsonObject();
    source.addProperty("receivedTimestamp", receivedTimestamp.toString());
    source.addProperty("topic", topic);
    return source;
  }
}