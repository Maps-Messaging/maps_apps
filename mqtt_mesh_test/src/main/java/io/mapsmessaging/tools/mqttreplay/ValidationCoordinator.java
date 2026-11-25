package io.mapsmessaging.tools.mqttreplay;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

  ValidationCoordinator(ReplayOptions options) {
    this.options = options;
    for (int index = 0; index < options.observeUrls().size(); index++) {
      names.add("observer-" + (index + 1));
    }
    engine = new ReplayValidationEngine(names);
  }

  void start() throws Exception {
    for (int index = 0; index < names.size(); index++) {
      MqttObservationClient client = new MqttObservationClient(names.get(index), options.observeUrls().get(index), engine);
      clients.add(client);
      client.start(options.observeTopic());
      System.out.printf("Subscriber %s connected to %s, subscribed to %s%n",
          names.get(index), options.observeUrls().get(index), options.observeTopic());
    }
  }

  String progress() {
    StringBuilder status = new StringBuilder();
    for (ReplayValidationEngine.ObserverProgress progress : engine.progress()) {
      status.append(" [").append(progress.name())
          .append(": received ").append(String.format("%,d", progress.received()))
          .append(", matched ").append(String.format("%,d", progress.matched()))
          .append("]");
    }
    return status.toString();
  }

  void published(ReplayEvent event) {
    engine.published(event.topic(), event.payload());
  }

  ValidationReport finish() throws Exception {
    if (!clients.isEmpty()) {
      TimeUnit.MILLISECONDS.sleep(options.drainMillis());
    }
    Map<String, String> errors = new LinkedHashMap<>();
    for (int index = 0; index < clients.size(); index++) {
      errors.put(names.get(index), clients.get(index).failure());
    }
    ValidationReport report = engine.snapshot(errors);
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
