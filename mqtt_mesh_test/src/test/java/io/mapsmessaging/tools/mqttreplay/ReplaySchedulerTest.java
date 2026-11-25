package io.mapsmessaging.tools.mqttreplay;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReplaySchedulerTest {
  @Test
  void noDelayIgnoresLongRecordedTimestampGaps() {
    ReplayScheduler scheduler = new ReplayScheduler(1.0, true);
    assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
      scheduler.await(Instant.parse("2026-08-01T00:00:00Z"));
      scheduler.await(Instant.parse("2026-09-01T00:00:00Z"));
    });
  }
}
