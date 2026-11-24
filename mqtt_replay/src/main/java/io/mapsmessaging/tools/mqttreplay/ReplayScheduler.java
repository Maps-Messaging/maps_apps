package io.mapsmessaging.tools.mqttreplay;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

final class ReplayScheduler {
  private final double speed;
  private final long started = System.nanoTime();
  private Instant first;

  ReplayScheduler(double speed) {
    this.speed = speed;
  }

  void await(Instant timestamp) throws InterruptedException {
    if (first == null) {
      first = timestamp;
    }
    long delta = Duration.between(first, timestamp).toNanos();
    long target = Math.max(0L, Math.round(delta / speed));
    long remaining = target - (System.nanoTime() - started);
    if (remaining > 0) {
      TimeUnit.NANOSECONDS.sleep(remaining);
    }
  }
}
