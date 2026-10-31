package io.mapsmessaging.apps.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordingRequestTest {

  @Test
  void parsesRequestAndAppliesDefaultCaptureWindow() {
    RecordingRequest request =
        RecordingRequest.fromJson(
            """
            {
              "requestId": "req-1",
              "eventId": "detect-7",
              "sourceId": "USV-002",
              "stream": "optical_view",
              "eventTime": "2026-09-21T10:00:00Z"
            }
            """,
            config());

    assertEquals("detect-7|optical_view", request.deduplicationKey());
    assertEquals(Instant.parse("2026-09-21T09:59:30Z"), request.startTime());
    assertEquals(90, request.durationSeconds());
  }

  @Test
  void requestCanOverrideCaptureWindow() {
    RecordingRequest request =
        RecordingRequest.fromJson(
            """
            {
              "requestId": "req-2",
              "sourceId": "USV-002",
              "stream": "thermal_view",
              "eventTime": "2026-09-21T10:00:00Z",
              "beforeSeconds": 10,
              "afterSeconds": 20
            }
            """,
            config());

    assertEquals("req-2", request.eventId());
    assertEquals(30, request.durationSeconds());
  }

  @Test
  void rejectsMissingStream() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordingRequest.fromJson(
                """
                {
                  "requestId": "req-3",
                  "sourceId": "USV-002",
                  "eventTime": "2026-09-21T10:00:00Z"
                }
                """,
                config()));
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
}
