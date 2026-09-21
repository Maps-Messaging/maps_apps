package io.mapsmessaging.apps.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class VideoRecorderConfigTest {

  @Test
  void parsesRequiredEnvironmentAndDefaults() {
    Map<String, String> env =
        Map.of(
            "MAPS_VIDEO_MQTT_URL", "tcp://127.0.0.1:1883",
            "MAPS_VIDEO_MEDIAMTX_URL", "http://127.0.0.1:9996/",
            "MAPS_VIDEO_S3_BUCKET", "video-bucket",
            "MAPS_VIDEO_S3_REGION", "eu-central-1");

    VideoRecorderConfig config = VideoRecorderConfig.parse(new String[0], env::get);

    assertEquals("tcp://127.0.0.1:1883", config.mqttUrl());
    assertEquals("http://127.0.0.1:9996", config.mediaMtxPlaybackUrl());
    assertEquals("maps/video/record/request", config.requestTopic());
    assertEquals("maps/video/record/result", config.resultTopic());
    assertEquals(30, config.defaultBeforeSeconds());
    assertEquals(60, config.defaultAfterSeconds());
    assertEquals(1800, config.dedupeRetentionSeconds());
    assertEquals(86400, config.urlValiditySeconds());
    assertNull(config.mqttUsername());
  }

  @Test
  void commandLineOverridesEnvironment() {
    Map<String, String> env =
        Map.of(
            "MAPS_VIDEO_MQTT_URL", "tcp://old:1883",
            "MAPS_VIDEO_MEDIAMTX_URL", "http://old:9996",
            "MAPS_VIDEO_S3_BUCKET", "old",
            "MAPS_VIDEO_S3_REGION", "eu-west-1");

    VideoRecorderConfig config =
        VideoRecorderConfig.parse(
            new String[] {
              "--mqtt-url", "tcp://new:1883",
              "--mediamtx-url", "http://new:9996",
              "--s3-bucket", "new",
              "--s3-region", "eu-central-1",
              "--before-seconds", "15",
              "--after-seconds", "45"
            },
            env::get);

    assertEquals("tcp://new:1883", config.mqttUrl());
    assertEquals("http://new:9996", config.mediaMtxPlaybackUrl());
    assertEquals("new", config.s3Bucket());
    assertEquals(15, config.defaultBeforeSeconds());
    assertEquals(45, config.defaultAfterSeconds());
  }

  @Test
  void rejectsUnknownArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () -> VideoRecorderConfig.parse(new String[] {"--no-such-option", "x"}, ignored -> null));
  }

  @Test
  void rejectsMissingRequiredConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> VideoRecorderConfig.parse(new String[0], ignored -> null));
  }
}
