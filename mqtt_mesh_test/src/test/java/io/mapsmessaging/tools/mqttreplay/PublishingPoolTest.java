package io.mapsmessaging.tools.mqttreplay;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishingPoolTest {
  @TempDir Path directory;

  private static ReplayEvent event(String topic, int value) {
    return new ReplayEvent(Instant.EPOCH, topic, new byte[]{(byte) value}, 0, false);
  }

  @Test
  void hashingAndOrder() throws Exception {
    List<Integer> values = new CopyOnWriteArrayList<>();
    assertEquals(Math.floorMod("topic".hashCode(), 3), PublishingPool.sessionIndex("topic", 3));
    try (PublishingPool pool = new PublishingPool(3, 2, index -> new PublishingPool.Session() {
      public void publish(ReplayEvent event) {
        assertEquals(PublishingPool.sessionIndex(event.topic(), 3), index);
        values.add((int) event.payload()[0]);
      }
      public void close() {}
    }, event -> {})) {
      for (int i = 0; i < 50; i++) pool.publish(event("topic", i));
      pool.drain();
      assertEquals(50, pool.completedCount());
      assertEquals(java.util.stream.IntStream.range(0, 50).boxed().toList(), values);
    }
  }

  @Test
  void boundedBackpressureAndConcurrency() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch other = new CountDownLatch(1);
    try (PublishingPool pool = new PublishingPool(2, 1, index -> new PublishingPool.Session() {
      public void publish(ReplayEvent event) throws Exception {
        if (event.topic().equals("a")) {
          entered.countDown();
          if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        } else other.countDown();
      }
      public void close() {}
    }, event -> {}); ExecutorService executor = Executors.newSingleThreadExecutor()) {
      try {
        pool.publish(event("a", 1));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        pool.publish(event("a", 2));
        pool.publish(event("b", 1));
        assertTrue(other.await(2, TimeUnit.SECONDS));
        Future<?> pending = executor.submit(() -> { pool.publish(event("a", 3)); return null; });
        assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
        release.countDown();
        pending.get(2, TimeUnit.SECONDS);
        pool.drain();
        assertEquals(4, pool.completedCount());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void failurePropagates() throws Exception {
    PublishingPool pool = new PublishingPool(1, 1, index -> new PublishingPool.Session() {
      public void publish(ReplayEvent event) { throw new IllegalStateException("failed"); }
      public void close() {}
    }, event -> fail("Failed publication must not be counted"));
    try {
      try {
        pool.publish(event("a", 1));
      } catch (IllegalStateException expected) {
        assertNotNull(expected.getCause());
      }
      assertThrows(IllegalStateException.class, pool::drain);
      assertEquals(0, pool.completedCount());
    } finally {
      assertThrows(IllegalStateException.class, pool::close);
    }
  }

  @Test
  void degradationThresholds() {
    long tenSeconds = TimeUnit.SECONDS.toNanos(10);
    assertTrue(PublishingPool.degraded(24, 100, true, tenSeconds));
    assertFalse(PublishingPool.degraded(25, 100, true, tenSeconds));
    assertFalse(PublishingPool.degraded(0, 100, false, tenSeconds));
    assertFalse(PublishingPool.degraded(0, 100, true, tenSeconds - 1));
  }

  @Test
  void degradationRequiresContinuousDemandAndResetsAfterRecovery() {
    PublishingPool.DegradationTracker tracker = new PublishingPool.DegradationTracker();
    long second = TimeUnit.SECONDS.toNanos(1);
    assertFalse(tracker.update(0, 100, true, 0));
    assertFalse(tracker.update(0, 100, true, 9 * second));
    assertTrue(tracker.update(0, 100, true, 10 * second));
    assertFalse(tracker.update(0, 100, true, 11 * second));
    assertFalse(tracker.update(100, 100, true, 12 * second));
    assertFalse(tracker.update(0, 100, true, 13 * second));
    assertFalse(tracker.update(0, 100, false, 23 * second));
    assertFalse(tracker.update(0, 100, true, 24 * second));
    assertTrue(tracker.update(0, 100, true, 34 * second));
    assertFalse(tracker.update(0, 0, true, 44 * second));
  }

  @Test
  void validatesCliAndPreservesExistingModes() {
    ReplayOptions defaults = options();
    assertEquals(0, defaults.sessions());
    assertEquals(100, defaults.queueDepth());
    assertEquals(0, defaults.qos());
    assertFalse(defaults.sessionPerTopic());
    assertNull(defaults.sessionPrefix());
    ReplayOptions configured = options("--sessions", "8", "--queue-depth", "3", "--qos", "2");
    assertEquals(8, configured.sessions());
    assertEquals(3, configured.queueDepth());
    assertEquals(2, configured.qos());
    assertTrue(options("--session-per-topic").sessionPerTopic());
    assertEquals("a/b", options("--session-prefix", "a/b/").sessionPrefix());
    assertThrows(IllegalArgumentException.class, () -> options("--sessions", "0"));
    assertThrows(IllegalArgumentException.class, () -> options("--sessions", "-1"));
    assertThrows(IllegalArgumentException.class, () -> options("--queue-depth", "0"));
    assertThrows(IllegalArgumentException.class, () -> options("--queue-depth", "-1"));
    assertThrows(IllegalArgumentException.class, () -> options("--sessions", "2", "--session-per-topic"));
    assertThrows(IllegalArgumentException.class, () -> options("--sessions", "2", "--session-prefix", "a/b"));
    assertThrows(IllegalArgumentException.class, () -> options("--qos", "3"));
  }

  private ReplayOptions options(String... extra) {
    List<String> arguments = new ArrayList<>(List.of("--dir", directory.toString(), "--broker", "tcp://localhost:1883"));
    arguments.addAll(List.of(extra));
    return ReplayOptions.parse(arguments.toArray(String[]::new));
  }

  @Test
  void drainsBeforeValidationAndSupportsObservationBeforePublishCompletion() throws Exception {
    ReplayValidationEngine engine = new ReplayValidationEngine(List.of("observer"));
    try (PublishingPool pool = new PublishingPool(2, 3, index -> new PublishingPool.Session() {
      public void publish(ReplayEvent event) {
        engine.received("observer", event.topic(), event.payload());
      }
      public void close() {}
    }, event -> engine.published(event.topic(), event.payload()))) {
      for (int i = 0; i < 100; i++) {
        pool.publish(event(i % 2 == 0 ? "a" : "b", i));
      }
      pool.drain();
      assertEquals(100, pool.completedCount());
      ValidationReport report = engine.snapshot(Map.of());
      assertEquals(100, report.published());
      assertTrue(report.successful());
      assertEquals(100, report.observers().get(0).matched());
    }
  }
  @Test
  void closeDrainsAllAcceptedEvents() throws Exception {
    List<ReplayEvent> published = new CopyOnWriteArrayList<>();
    PublishingPool pool = new PublishingPool(2, 4, index -> new PublishingPool.Session() {
      public void publish(ReplayEvent event) {
        published.add(event);
      }
      public void close() {}
    }, event -> {});
    try (pool) {
      for (int i = 0; i < 100; i++) {
        pool.publish(event(i % 2 == 0 ? "a" : "b", i));
      }
    }
    assertEquals(100, pool.completedCount());
    assertEquals(100, published.size());
    assertThrows(IllegalStateException.class, () -> pool.publish(event("a", 1)));
  }

  @Test
  void releasesBlockedReaderOnPublishingFailure() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    PublishingPool pool = new PublishingPool(1, 2, index -> new PublishingPool.Session() {
      public void publish(ReplayEvent event) throws Exception {
        entered.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
        throw new IllegalStateException("publish failed");
      }
      public void close() {}
    }, event -> fail("Failed publication must not be counted"));
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      try {
        pool.publish(event("a", 1));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        pool.publish(event("a", 2));
        pool.publish(event("a", 3));
        Future<?> pending = executor.submit(() -> { pool.publish(event("a", 4)); return null; });
        assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
        release.countDown();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(0, pool.completedCount());
      } finally {
        release.countDown();
      }
    } finally {
      assertThrows(IllegalStateException.class, pool::close);
    }
  }

  @Test
  void interruptedBackpressureDoesNotDiscardAcceptedEvents() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch attempted = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    try (PublishingPool pool = new PublishingPool(1, 1, index -> new PublishingPool.Session() {
      public void publish(ReplayEvent event) throws Exception {
        entered.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
      }
      public void close() {}
    }, event -> {})) {
      try {
        pool.publish(event("a", 1));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        pool.publish(event("a", 2));
        Thread reader = new Thread(() -> {
          attempted.countDown();
          try {
            pool.publish(event("a", 3));
          } catch (InterruptedException expected) {
            interrupted.countDown();
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
        reader.start();
        assertTrue(attempted.await(2, TimeUnit.SECONDS));
        reader.interrupt();
        reader.join(2000);
        assertFalse(reader.isAlive());
        assertEquals(0, interrupted.getCount());
      } finally {
        release.countDown();
      }
      pool.drain();
      assertEquals(2, pool.completedCount());
    }
  }
}
