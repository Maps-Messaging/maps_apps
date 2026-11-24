package io.mapsmessaging.tools.mqttreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayValidationEngineTest {
  private static byte[] data(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void matchesIndependentTopicsDespiteReorderedObservations() {
    ReplayValidationEngine engine = new ReplayValidationEngine(List.of("s1", "s3"));
    engine.published("/a", data("1"));
    engine.published("/b", data("2"));
    engine.received("s1", "/b", data("2"));
    engine.received("s1", "/a", data("1"));
    engine.received("s3", "/a", data("1"));
    engine.received("s3", "/b", data("2"));
    ValidationReport report = engine.snapshot(Map.of());
    assertEquals(2, report.published());
    assertTrue(report.successful());
    assertEquals(2, report.observers().get(0).matched());
    assertEquals(2, report.observers().get(1).matched());
  }

  @Test
  void reportsMissingExcessAndUnknownOccurrencesSeparately() {
    ReplayValidationEngine engine = new ReplayValidationEngine(List.of("s1"));
    engine.published("/a", data("same"));
    engine.published("/a", data("same"));
    engine.published("/b", data("missing"));
    engine.received("s1", "/a", data("same"));
    engine.received("s1", "/a", data("same"));
    engine.received("s1", "/a", data("same"));
    engine.received("s1", "/other", data("unexpected"));
    ValidationReport.ObserverResult result = engine.snapshot(Map.of()).observers().get(0);
    assertEquals(4, result.received());
    assertEquals(2, result.matched());
    assertEquals(1, result.missing());
    assertEquals(1, result.excess());
    assertEquals(1, result.unexpected());
    assertFalse(result.successful());
  }

  @Test
  void recordsObserverErrors() {
    ReplayValidationEngine engine = new ReplayValidationEngine(List.of("s1"));
    ValidationReport.ObserverResult result = engine.snapshot(Map.of("s1", "Connection lost")).observers().get(0);
    assertEquals("Connection lost", result.error());
    assertFalse(result.successful());
  }
}
