package io.mapsmessaging.tools.mqttreplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Reconciles observations as they arrive without retaining fully matched message keys. */
final class ReplayValidationEngine {
  private static final int MAX_MISMATCH_SAMPLES = 100;
  private final Map<String, ObserverState> observers = new LinkedHashMap<>();
  private final LongAdder sent = new LongAdder();

  ReplayValidationEngine(List<String> observerNames) {
    for (String name : observerNames) {
      if (observers.putIfAbsent(name, new ObserverState()) != null) {
        throw new IllegalArgumentException("Duplicate observer name: " + name);
      }
    }
  }

  void published(String topic, byte[] payload) {
    MessageKey key = MessageKey.of(topic, payload);
    for (ObserverState observer : observers.values()) {
      observer.pending.compute(key, (ignored, count) -> {
        long previous = count == null ? 0 : count;
        if (previous < 0) {
          observer.matched.increment();
        }
        long remaining = previous + 1;
        return remaining == 0 ? null : remaining;
      });
    }
    sent.increment();
  }

  void received(String observerName, String topic, byte[] payload) {
    ObserverState observer = observers.get(observerName);
    if (observer == null) {
      throw new IllegalArgumentException("Unknown observer: " + observerName);
    }
    MessageKey key = MessageKey.of(topic, payload);
    observer.pending.compute(key, (ignored, count) -> {
      long previous = count == null ? 0 : count;
      if (previous > 0) {
        observer.matched.increment();
      }
      long remaining = previous - 1;
      return remaining == 0 ? null : remaining;
    });
    observer.received.increment();
  }

  ValidationReport snapshot(Map<String, String> observerErrors) {
    List<ValidationReport.ObserverResult> results = new ArrayList<>();
    for (Map.Entry<String, ObserverState> entry : observers.entrySet()) {
      String name = entry.getKey();
      ObserverState observer = entry.getValue();
      long missing = 0;
      long unexpected = 0;
      long mismatchKeys = 0;
      List<ValidationReport.Mismatch> samples = new ArrayList<>();
      for (Map.Entry<MessageKey, Long> item : observer.pending.entrySet()) {
        long difference = item.getValue();
        if (difference == 0) {
          continue;
        }
        mismatchKeys++;
        if (difference > 0) {
          missing += difference;
        } else {
          unexpected -= difference;
        }
        if (samples.size() < MAX_MISMATCH_SAMPLES) {
          samples.add(new ValidationReport.Mismatch(item.getKey().topic(), item.getKey().payloadHash(),
              Math.max(0L, difference), Math.max(0L, -difference)));
        }
      }
      results.add(new ValidationReport.ObserverResult(name, observer.received.sum(), observer.matched.sum(),
          missing, 0, unexpected, observerErrors.get(name), mismatchKeys, samples));
    }
    return new ValidationReport(sent.sum(), results);
  }

  int pendingKeyCount(String observerName) {
    ObserverState observer = observers.get(observerName);
    if (observer == null) {
      throw new IllegalArgumentException("Unknown observer: " + observerName);
    }
    return observer.pending.size();
  }

  private static final class ObserverState {
    private final ConcurrentHashMap<MessageKey, Long> pending = new ConcurrentHashMap<>();
    private final LongAdder received = new LongAdder();
    private final LongAdder matched = new LongAdder();
  }
}
