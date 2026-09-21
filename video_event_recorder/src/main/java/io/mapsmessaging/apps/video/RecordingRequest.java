package io.mapsmessaging.apps.video;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;

public record RecordingRequest(
    String requestId,
    String eventId,
    String sourceId,
    String stream,
    Instant eventTime,
    int beforeSeconds,
    int afterSeconds) {

  public static RecordingRequest fromJson(String json, VideoRecorderConfig config) {
    JsonObject object;
    try {
      object = JsonParser.parseString(json).getAsJsonObject();
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Recording request is not a JSON object", exception);
    }

    String requestId = requiredString(object, "requestId");
    String eventId = optionalString(object, "eventId", requestId);
    String sourceId = requiredString(object, "sourceId");
    String stream = requiredString(object, "stream");
    String eventTimeText = requiredString(object, "eventTime");

    Instant eventTime;
    try {
      eventTime = Instant.parse(eventTimeText);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("eventTime must be an RFC3339 timestamp", exception);
    }

    int beforeSeconds = optionalInteger(object, "beforeSeconds", config.defaultBeforeSeconds());
    int afterSeconds = optionalInteger(object, "afterSeconds", config.defaultAfterSeconds());
    validateDuration("beforeSeconds", beforeSeconds);
    validateDuration("afterSeconds", afterSeconds);
    if (beforeSeconds + afterSeconds <= 0) {
      throw new IllegalArgumentException("beforeSeconds and afterSeconds cannot both be zero");
    }

    return new RecordingRequest(
        requestId, eventId, sourceId, stream, eventTime, beforeSeconds, afterSeconds);
  }

  public String deduplicationKey() {
    return eventId + "|" + stream;
  }

  public Instant startTime() {
    return eventTime.minusSeconds(beforeSeconds);
  }

  public int durationSeconds() {
    return beforeSeconds + afterSeconds;
  }

  private static String requiredString(JsonObject object, String name) {
    String value = optionalString(object, name, null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required field: " + name);
    }
    return value;
  }

  private static String optionalString(JsonObject object, String name, String defaultValue) {
    JsonElement element = object.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException(name + " must be a string");
    }
    return element.getAsString();
  }

  private static int optionalInteger(JsonObject object, String name, int defaultValue) {
    JsonElement element = object.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    try {
      return element.getAsInt();
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(name + " must be an integer", exception);
    }
  }

  private static void validateDuration(String name, int value) {
    if (value < 0 || value > 86400) {
      throw new IllegalArgumentException(name + " must be between 0 and 86400");
    }
  }
}
