package io.mapsmessaging.tools.mqttreplay;

import java.util.List;

record ValidationReport(long published, List<ObserverResult> observers, List<SessionResult> sessions) {
  ValidationReport(long published, List<ObserverResult> observers) {
    this(published, observers, List.of());
  }

  record SessionResult(String name, int topics, int filters, long assigned, long expected,
                       boolean connected, ObserverResult validation) {
  }
  record ObserverResult(String name, long received, long matched, long missing,
                        long excess, long unexpected, String error, long mismatchKeys, List<Mismatch> mismatchSamples) {
    boolean successful() {
      return missing == 0 && excess == 0 && unexpected == 0 && error == null;
    }
  }

  record Mismatch(String topic, String payloadHash, long expected, long received) {
  }

  boolean successful() {
    return observers.stream().allMatch(ObserverResult::successful);
  }
}
