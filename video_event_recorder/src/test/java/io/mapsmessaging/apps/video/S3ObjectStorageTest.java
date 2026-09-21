package io.mapsmessaging.apps.video;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class S3ObjectStorageTest {

  @Test
  void buildsStableSanitizedObjectKey() {
    RecordingRequest request =
        new RecordingRequest(
            "req:1",
            "detect/1",
            "USV-002",
            "optical/view",
            Instant.parse("2026-09-21T10:00:00Z"),
            30,
            60);

    assertEquals(
        "detections/2026/09/21/USV-002/detect_1/optical_view-req_1.mp4",
        S3ObjectStorage.objectKey("/detections/", request));
  }
}
