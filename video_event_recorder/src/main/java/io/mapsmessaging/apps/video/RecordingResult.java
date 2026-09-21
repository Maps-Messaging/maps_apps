package io.mapsmessaging.apps.video;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Instant;

public record RecordingResult(
    String requestId,
    String eventId,
    String sourceId,
    String stream,
    Status status,
    String contentType,
    URI url,
    String objectKey,
    Instant startTime,
    int durationSeconds,
    String message) {

  private static final Gson GSON = new Gson();

  public enum Status {
    COMPLETE,
    FAILED
  }

  public static RecordingResult complete(
      RecordingRequest request, ObjectStorage.StoredObject storedObject) {
    return new RecordingResult(
        request.requestId(),
        request.eventId(),
        request.sourceId(),
        request.stream(),
        Status.COMPLETE,
        "video/mp4",
        storedObject.url(),
        storedObject.objectKey(),
        request.startTime(),
        request.durationSeconds(),
        null);
  }

  public static RecordingResult failed(RecordingRequest request, Throwable failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      message = failure.getClass().getSimpleName();
    }
    return new RecordingResult(
        request.requestId(),
        request.eventId(),
        request.sourceId(),
        request.stream(),
        Status.FAILED,
        null,
        null,
        null,
        request.startTime(),
        request.durationSeconds(),
        message);
  }

  public String toJson() {
    JsonObject object = new JsonObject();
    object.addProperty("requestId", requestId);
    object.addProperty("eventId", eventId);
    object.addProperty("sourceId", sourceId);
    object.addProperty("stream", stream);
    object.addProperty("status", status.name());
    if (contentType != null) {
      object.addProperty("contentType", contentType);
    }
    if (url != null) {
      object.addProperty("url", url.toString());
    }
    if (objectKey != null) {
      object.addProperty("objectKey", objectKey);
    }
    object.addProperty("startTime", startTime.toString());
    object.addProperty("durationSeconds", durationSeconds);
    if (message != null) {
      object.addProperty("message", message);
    }
    return GSON.toJson(object);
  }
}
