package io.mapsmessaging.apps.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class VideoEventRecorderTest {

  @Test
  void successfulRecordingPublishesCompleteResultAndDeletesTempFile() throws Exception {
    VideoRecorderConfig config = config();
    FakeTransport transport = new FakeTransport();
    Path clip = Files.createTempFile("video-recorder-test-", ".mp4");
    MediaMtxClient media = (stream, start, duration) -> clip;
    ObjectStorage storage =
        (file, request) ->
            new ObjectStorage.StoredObject(
                "detections/test.mp4", URI.create("https://example.invalid/video.mp4"));

    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    VideoEventRecorder recorder =
        new VideoEventRecorder(
            config,
            transport,
            media,
            storage,
            new DeduplicationRegistry(),
            scheduler,
            Clock.systemUTC(),
            0L);
    try {
      recorder.process(request());

      assertEquals(1, transport.published.size());
      JsonObject result = JsonParser.parseString(transport.published.get(0)).getAsJsonObject();
      assertEquals("COMPLETE", result.get("status").getAsString());
      assertEquals("https://example.invalid/video.mp4", result.get("url").getAsString());
      assertFalse(Files.exists(clip));
    } finally {
      recorder.close();
    }
  }

  @Test
  void mediaFailurePublishesFailedResult() throws Exception {
    FakeTransport transport = new FakeTransport();
    MediaMtxClient media =
        (stream, start, duration) -> {
          throw new IOException("recording unavailable");
        };

    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    VideoEventRecorder recorder =
        new VideoEventRecorder(
            config(),
            transport,
            media,
            (file, request) -> {
              throw new AssertionError("storage should not be called");
            },
            new DeduplicationRegistry(),
            scheduler,
            Clock.systemUTC(),
            0L);
    try {
      recorder.process(request());

      JsonObject result = JsonParser.parseString(transport.published.get(0)).getAsJsonObject();
      assertEquals("FAILED", result.get("status").getAsString());
      assertEquals("recording unavailable", result.get("message").getAsString());
    } finally {
      recorder.close();
    }
  }

  @Test
  void resultPublicationIsRetried() throws Exception {
    FakeTransport transport = new FakeTransport();
    transport.failuresBeforeSuccess = 2;
    Path clip = Files.createTempFile("video-recorder-test-", ".mp4");

    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    VideoEventRecorder recorder =
        new VideoEventRecorder(
            config(),
            transport,
            (stream, start, duration) -> clip,
            (file, request) ->
                new ObjectStorage.StoredObject(
                    "detections/test.mp4", URI.create("https://example.invalid/video.mp4")),
            new DeduplicationRegistry(),
            scheduler,
            Clock.systemUTC(),
            0L);
    try {
      recorder.process(request());

      assertEquals(3, transport.publishAttempts);
      assertEquals(1, transport.published.size());
    } finally {
      recorder.close();
    }
  }

  @Test
  void duplicateEventAndStreamSchedulesOnlyOneJob() throws Exception {
    FakeTransport transport = new FakeTransport();
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    Instant now = Instant.parse("2026-09-21T10:00:00Z");

    VideoEventRecorder recorder =
        new VideoEventRecorder(
            config(),
            transport,
            (stream, start, duration) -> {
              throw new AssertionError("scheduled job should not execute in this test");
            },
            (file, request) -> {
              throw new AssertionError("storage should not execute in this test");
            },
            new DeduplicationRegistry(),
            scheduler,
            Clock.fixed(now, ZoneOffset.UTC),
            0L);
    try {
      String payload =
          """
          {
            "requestId": "req-1",
            "eventId": "detect-1",
            "sourceId": "USV-002",
            "stream": "optical_view",
            "eventTime": "2026-09-21T10:10:00Z",
            "beforeSeconds": 30,
            "afterSeconds": 60
          }
          """;

      recorder.onRequest(payload);
      recorder.onRequest(payload);

      assertEquals(1, scheduler.getQueue().size());
    } finally {
      recorder.close();
    }
  }

  private static RecordingRequest request() {
    return new RecordingRequest(
        "req-1",
        "detect-1",
        "USV-002",
        "optical_view",
        Instant.parse("2026-09-21T10:00:00Z"),
        30,
        60);
  }

  private static VideoRecorderConfig config() {
    Map<String, String> env =
        Map.of(
            "MAPS_VIDEO_MQTT_URL", "tcp://127.0.0.1:1883",
            "MAPS_VIDEO_MEDIAMTX_URL", "http://127.0.0.1:9996",
            "MAPS_VIDEO_S3_BUCKET", "video-bucket",
            "MAPS_VIDEO_S3_REGION", "eu-central-1");
    return VideoRecorderConfig.parse(new String[0], env::get);
  }

  private static final class FakeTransport implements RecordingTransport {

    private final List<String> published = new ArrayList<>();
    private int failuresBeforeSuccess;
    private int publishAttempts;

    @Override
    public void start(Consumer<String> requestHandler) {}

    @Override
    public void publish(String payload) throws Exception {
      publishAttempts++;
      if (publishAttempts <= failuresBeforeSuccess) {
        throw new IOException("publish failed");
      }
      published.add(payload);
    }

    @Override
    public void close() {}
  }
}
