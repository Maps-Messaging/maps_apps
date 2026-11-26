package io.mapsmessaging.tools.mqttreplay;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** A dedicated subscription at one independently configured observation point. */
final class MqttObservationClient implements AutoCloseable {
  private static final long OPERATION_TIMEOUT_MS = 15_000;
  private final String name;
  private final String version;
  private final MqttWireClient client;
  private final AtomicReference<String> failure = new AtomicReference<>();
  private final AtomicLong lastReceived = new AtomicLong();

  MqttObservationClient(String name, String url, ReplayValidationEngine engine) throws Exception {
    this(name, url, engine, "3.1.1");
  }

  MqttObservationClient(String name, String url, ReplayValidationEngine engine, String version) throws Exception {
    this.name = name;
    this.version = version;
    client = MqttWireClient.create(version, url, "maps-mqtt-mesh-observer-" + UUID.randomUUID(), OPERATION_TIMEOUT_MS,
        (topic, payload) -> {
          lastReceived.set(System.nanoTime());
          engine.received(name, topic, payload);
        }, this::recordFailure);
  }

  private void recordFailure(String message) {
    String detail = "MQTT " + version + ": " + message;
    if (failure.compareAndSet(null, detail)) {
      System.err.println(name + ": " + detail);
    }
  }

  void start(List<String> filters) throws Exception {
    try {
      client.connect();
      for (String filter : filters) {
        client.subscribe(filter, 1);
      }
    } catch (Exception exception) {
      recordFailure(exception.getMessage());
      throw new IllegalStateException(name + ": " + failure.get(), exception);
    }
  }

  boolean connected() {
    return client.isConnected();
  }

  long lastReceived() {
    return lastReceived.get();
  }

  String failure() {
    return failure.get();
  }

  @Override
  public void close() throws Exception {
    client.close();
  }
}
