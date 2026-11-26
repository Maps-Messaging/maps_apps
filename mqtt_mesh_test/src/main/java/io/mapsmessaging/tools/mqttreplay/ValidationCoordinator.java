package io.mapsmessaging.tools.mqttreplay;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Starts all subscriptions before replay and reconciles them after a bounded drain period. */
final class ValidationCoordinator implements AutoCloseable {
  private final ReplayOptions options;
  private final ReplayValidationEngine engine;
  private final List<MqttObservationClient> clients = new ArrayList<>();
  private final List<String> names = new ArrayList<>();
  private final List<SubscriptionPlanner.Assignment> assignments;
  private final Map<String, List<String>> routes = new HashMap<>();
  private final Map<String, Long> previousReceived = new HashMap<>();
  private final Map<String, Long> behindSince = new HashMap<>();
  private final Map<String, Boolean> degraded = new HashMap<>();
  private long lastProgress = System.nanoTime();

  ValidationCoordinator(ReplayOptions options, Map<String, Long> counts) {
    this.options = options;
    assignments = options.observeUrls().isEmpty() ? List.of()
        : SubscriptionPlanner.plan(counts, options.subscriberSessions(), options.observeTopic());
    for (int broker = 0; broker < options.observeUrls().size(); broker++) {
      for (int session = 0; session < assignments.size(); session++) {
        String name = "observer-" + (broker + 1) + "-session-" + (session + 1);
        names.add(name);
        for (SubscriptionPlanner.Filter filter : assignments.get(session).filters()) {
          for (String topic : filter.topics()) {
            routes.computeIfAbsent(topic, ignored -> new ArrayList<>()).add(name);
          }
        }
      }
    }
    engine = new ReplayValidationEngine(names);
    if (!assignments.isEmpty()) {
      reportAllocation(counts);
    }
  }

  private void reportAllocation(Map<String, Long> counts) {
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    long[] loads = assignments.stream().mapToLong(SubscriptionPlanner.Assignment::events).sorted().toArray();
    double mean = (double) total / loads.length;
    double median = loads.length % 2 == 0
        ? loads[loads.length / 2 - 1] / 2.0 + loads[loads.length / 2] / 2.0 : loads[loads.length / 2];
    System.out.printf("Preflight: %,d events, %,d topics, %d brokers, %d sessions per broker, %d filters per broker%n",
        total, counts.size(), options.observeUrls().size(), loads.length,
        assignments.stream().mapToInt(item -> item.filters().size()).sum());
    System.out.printf("Load min %,d, median %,.1f, max %,d, imbalance %.2f%% (range / mean)%n",
        loads[0], median, loads[loads.length - 1], mean == 0 ? 0 : 100.0 * (loads[loads.length - 1] - loads[0]) / mean);
    counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
        .thenComparing(Map.Entry.comparingByKey())).findFirst().ifPresent(item -> {
          System.out.printf("Largest topic %s: %,d events (%.2f%%)%n", item.getKey(), item.getValue(), 100.0 * item.getValue() / total);
          if (item.getValue() > mean) {
            System.out.println("Unavoidable imbalance: dominant topic exceeds mean session load");
          }
        });
    for (int i = 0; i < assignments.size(); i++) {
      SubscriptionPlanner.Assignment assignment = assignments.get(i);
      System.out.printf("Session %d: %,d expected events, %d topics, %d filters%n",
          i + 1, assignment.events(), assignment.topicCount(), assignment.filters().size());
      for (String filter : assignment.values()) {
        if (filter.contains("#") || filter.contains("+")) {
          System.out.println("Warning: wildcard may match unrecorded topics: " + filter);
        }
      }
    }
  }

  void start() throws Exception {
    for (int index = 0; index < names.size(); index++) {
      int broker = index / assignments.size();
      List<String> filters = assignments.get(index % assignments.size()).values();
      MqttObservationClient client = new MqttObservationClient(names.get(index), options.observeUrls().get(broker), engine, options.mqttVersion());
      clients.add(client);
      client.start(filters);
      System.out.printf("Subscriber %s connected to %s (MQTT %s), subscribed to %d filters%n",
          names.get(index), options.observeUrls().get(broker), options.mqttVersion(), filters.size());
    }
    for (MqttObservationClient client : clients) {
      if (!client.connected() || client.failure() != null) {
        throw new IllegalStateException("Subscriber disconnected during startup: " + client.failure());
      }
    }
  }

  String progress() {
    StringBuilder status = new StringBuilder();
    long now = System.nanoTime();
    double seconds = Math.max(1L, now - lastProgress) / 1_000_000_000.0;
    List<ReplayValidationEngine.ObserverProgress> progress = engine.progress();
    for (int i = 0; i < progress.size(); i++) {
      ReplayValidationEngine.ObserverProgress item = progress.get(i);
      MqttObservationClient client = clients.get(i);
      String name = item.name();
      long delta = item.received() - previousReceived.getOrDefault(name, 0L);
      previousReceived.put(name, item.received());
      long pending = Math.max(0, item.expected() - item.matched());
      if (pending > 0 && delta == 0) {
        long since = behindSince.computeIfAbsent(name, ignored -> now);
        if (now - since >= TimeUnit.SECONDS.toNanos(10) && !degraded.getOrDefault(name, false)) {
          degraded.put(name, true);
          System.err.printf("%s: sustained outstanding demand, %,d unmatched%n", name, pending);
        }
      } else {
        behindSince.remove(name);
        if (Boolean.TRUE.equals(degraded.remove(name))) {
          System.out.println(name + ": receive progress recovered");
        }
      }
      long last = client.lastReceived();
      status.append(String.format(" [%s MQTT %s: expected %,d, received %,d, matched %,d, %,.0f/s, idle %s, connected %s]",
          name, options.mqttVersion(), item.expected(), item.received(), item.matched(), delta / seconds,
          last == 0 ? "never" : TimeUnit.NANOSECONDS.toMillis(Math.max(0, now - last)) + "ms", client.connected()));
    }
    lastProgress = now;
    return status.toString();
  }

  void published(ReplayEvent event) {
    List<String> targets = routes.get(event.topic());
    if (targets == null) {
      throw new IllegalStateException("Replay topic absent from subscription plan: " + event.topic());
    }
    engine.published(event.topic(), event.payload(), targets);
  }

  ValidationReport finish() throws Exception {
    if (!clients.isEmpty()) {
      TimeUnit.MILLISECONDS.sleep(options.drainMillis());
    }
    Map<String, String> errors = new LinkedHashMap<>();
    for (int i = 0; i < clients.size(); i++) {
      MqttObservationClient client = clients.get(i);
      errors.put(names.get(i), client.failure() == null && !client.connected() ? "MQTT " + options.mqttVersion() + ": Disconnected" : client.failure());
    }
    ValidationReport individual = engine.snapshot(errors);
    List<ValidationReport.SessionResult> sessions = new ArrayList<>();
    List<ValidationReport.ObserverResult> observers = new ArrayList<>();
    List<ReplayValidationEngine.ObserverProgress> progress = engine.progress();
    for (int broker = 0; broker < options.observeUrls().size(); broker++) {
      long received = 0, matched = 0, missing = 0, excess = 0, unexpected = 0, keys = 0;
      List<ValidationReport.Mismatch> samples = new ArrayList<>();
      List<String> failures = new ArrayList<>();
      for (int session = 0; session < assignments.size(); session++) {
        int index = broker * assignments.size() + session;
        ValidationReport.ObserverResult item = individual.observers().get(index);
        SubscriptionPlanner.Assignment assignment = assignments.get(session);
        sessions.add(new ValidationReport.SessionResult(item.name(), assignment.topicCount(), assignment.filters().size(),
            assignment.events(), progress.get(index).expected(), clients.get(index).connected(), item));
        received += item.received();
        matched += item.matched();
        missing += item.missing();
        excess += item.excess();
        unexpected += item.unexpected();
        keys += item.mismatchKeys();
        if (item.error() != null) {
          failures.add(item.name() + ": " + item.error());
        }
        for (ValidationReport.Mismatch sample : item.mismatchSamples()) {
          if (samples.size() < 100) {
            samples.add(sample);
          }
        }
      }
      observers.add(new ValidationReport.ObserverResult("observer-" + (broker + 1), received, matched, missing,
          excess, unexpected, failures.isEmpty() ? null : String.join("; ", failures), keys, samples));
    }
    ValidationReport report = new ValidationReport(individual.published(), observers, sessions, options.mqttVersion());
    String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
    System.out.println(json);
    Path reportFile = options.report();
    if (reportFile != null) {
      Files.writeString(reportFile, json + System.lineSeparator(), StandardCharsets.UTF_8);
    }
    return report;
  }

  @Override
  public void close() throws Exception {
    Exception failure = null;
    for (MqttObservationClient client : clients) {
      try {
        client.close();
      } catch (Exception exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
