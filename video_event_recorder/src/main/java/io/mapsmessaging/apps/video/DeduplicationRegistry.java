package io.mapsmessaging.apps.video;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class DeduplicationRegistry {

  private final ConcurrentHashMap<String, Instant> entries = new ConcurrentHashMap<>();

  boolean claim(String key, Instant now, Duration retention) {
    entries.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));

    AtomicBoolean claimed = new AtomicBoolean(false);
    entries.compute(
        key,
        (ignored, expiresAt) -> {
          if (expiresAt == null || !expiresAt.isAfter(now)) {
            claimed.set(true);
            return now.plus(retention);
          }
          return expiresAt;
        });
    return claimed.get();
  }

  int size() {
    return entries.size();
  }
}
