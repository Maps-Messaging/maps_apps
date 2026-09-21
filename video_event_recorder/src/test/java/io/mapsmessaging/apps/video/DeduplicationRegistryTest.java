package io.mapsmessaging.apps.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeduplicationRegistryTest {

  @Test
  void suppressesDuplicateUntilRetentionExpires() {
    DeduplicationRegistry registry = new DeduplicationRegistry();
    Instant now = Instant.parse("2026-09-21T10:00:00Z");

    assertTrue(registry.claim("detect-1|optical", now, Duration.ofSeconds(30)));
    assertFalse(
        registry.claim("detect-1|optical", now.plusSeconds(29), Duration.ofSeconds(30)));
    assertTrue(
        registry.claim("detect-1|optical", now.plusSeconds(30), Duration.ofSeconds(30)));
    assertEquals(1, registry.size());
  }

  @Test
  void differentStreamsForSameEventAreIndependent() {
    DeduplicationRegistry registry = new DeduplicationRegistry();
    Instant now = Instant.parse("2026-09-21T10:00:00Z");

    assertTrue(registry.claim("detect-1|optical", now, Duration.ofMinutes(1)));
    assertTrue(registry.claim("detect-1|thermal", now, Duration.ofMinutes(1)));
    assertEquals(2, registry.size());
  }
}
