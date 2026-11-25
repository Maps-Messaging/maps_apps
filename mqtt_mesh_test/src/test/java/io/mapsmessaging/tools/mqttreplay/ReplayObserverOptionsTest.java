package io.mapsmessaging.tools.mqttreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayObserverOptionsTest {
  @TempDir Path directory;

  @Test
  void defaultsToReplayOnly() {
    ReplayOptions options = ReplayOptions.parse(new String[]{
        "--dir", directory.toString(), "--broker", "tcp://localhost:1883"});
    assertFalse(options.noDelay());
    assertEquals(0, options.observeUrls().size());
    assertEquals("#", options.observeTopic());
    assertEquals(5000, options.drainMillis());
  }

  @Test
  void supportsMultipleIndependentObservers() {
    ReplayOptions options = ReplayOptions.parse(new String[]{
        "--dir", directory.toString(), "--broker", "tcp://s1:1883",
        "--observe", "tcp://s1:1883", "--observe", "tcp://s3:1883",
        "--observe-topic", "/repmus/#", "--drain-ms", "9000", "--report", "report.json"});
    assertEquals(2, options.observeUrls().size());
    assertEquals("/repmus/#", options.observeTopic());
    assertEquals(9000, options.drainMillis());
    assertEquals(Path.of("report.json"), options.report());
  }

  @Test
  void acceptsNoDelayAlongsideSpeed() {
    ReplayOptions options = ReplayOptions.parse(new String[]{
        "--dir", directory.toString(), "--broker", "tcp://localhost:1883",
        "--speed", "400", "--no-delay"});
    assertTrue(options.noDelay());
    assertEquals(400.0, options.speed());
  }

  @Test
  void rejectsNegativeDrainTime() {
    assertThrows(IllegalArgumentException.class, () -> ReplayOptions.parse(new String[]{
        "--dir", directory.toString(), "--broker", "tcp://localhost:1883", "--drain-ms", "-1"}));
  }
}
