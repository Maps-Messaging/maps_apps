package io.mapsmessaging.tools.mqttreplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Records observations without assuming that different MQTT topics arrive in publication order. */
final class ReplayValidationEngine {
  private static final int MAX_MISMATCH_SAMPLES = 100;
  private final ConcurrentHashMap<MessageKey, LongAdder> expected = new ConcurrentHashMap<>();
  private final Map<String, ConcurrentHashMap<MessageKey, LongAdder>> observed = new LinkedHashMap<>();
  private final Map<String, LongAdder> totals = new LinkedHashMap<>();
  private final LongAdder sent = new LongAdder();

  ReplayValidationEngine(List<String> observerNames) {
    for (String name : observerNames) {
      if (observed.putIfAbsent(name, new ConcurrentHashMap<>()) != null) {
        throw new IllegalArgumentException("Duplicate observer name: " + name);
      }
      totals.put(name, new LongAdder());
    }
  }

  void published(String topic, byte[] payload) {
    expected.computeIfAbsent(MessageKey.of(topic, payload), ignored -> new LongAdder()).increment();
    sent.increment();
  }

  void received(String observerName, String topic, byte[] payload) {
    ConcurrentHashMap<MessageKey, LongAdder> counts = observed.get(observerName);
    if (counts == null) {
      throw new IllegalArgumentException("Unknown observer: " + observerName);
    }
    counts.computeIfAbsent(MessageKey.of(topic, payload), ignored -> new LongAdder()).increment();
    totals.get(observerName).increment();
  }

  ValidationReport snapshot(Map<String, String> observerErrors) {
    Map<MessageKey, Long> expectedCounts = copy(expected);
    List<ValidationReport.ObserverResult> results = new ArrayList<>();
    for (Map.Entry<String, ConcurrentHashMap<MessageKey, LongAdder>> entry : observed.entrySet()) {
      String name = entry.getKey();
      Map<MessageKey, Long> receivedCounts = copy(entry.getValue());
      long matched = 0;
      long missing = 0;
      long excess = 0;
      long unexpected = 0;
      long mismatchKeys = 0;
      List<ValidationReport.Mismatch> samples = new ArrayList<>();
      for (Map.Entry<MessageKey, Long> item : expectedCounts.entrySet()) {
        long actual = receivedCounts.getOrDefault(item.getKey(), 0L);
        matched += Math.min(item.getValue(), actual);
        missing += Math.max(0L, item.getValue() - actual);
        excess += Math.max(0L, actual - item.getValue());
        if (actual != item.getValue()) {
          mismatchKeys++;
          if (samples.size() < MAX_MISMATCH_SAMPLES) {
            samples.add(new ValidationReport.Mismatch(item.getKey().topic(), item.getKey().payloadHash(), item.getValue(), actual));
          }
        }
      }
      for (Map.Entry<MessageKey, Long> item : receivedCounts.entrySet()) {
        if (!expectedCounts.containsKey(item.getKey())) {
          unexpected += item.getValue();
          mismatchKeys++;
          if (samples.size() < MAX_MISMATCH_SAMPLES) {
            samples.add(new ValidationReport.Mismatch(item.getKey().topic(), item.getKey().payloadHash(), 0, item.getValue()));
          }
        }
      }
      results.add(new ValidationReport.ObserverResult(name, totals.get(name).sum(), matched, missing,
          excess, unexpected, observerErrors.get(name), mismatchKeys, samples));
    }
    return new ValidationReport(sent.sum(), results);
  }

  private static Map<MessageKey, Long> copy(ConcurrentHashMap<MessageKey, LongAdder> source) {
    Map<MessageKey, Long> result = new LinkedHashMap<>();
    source.forEach((key, counter) -> result.put(key, counter.sum()));
    return result;
  }
}
