package io.mapsmessaging.tools.mqttreplay;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** A dedicated subscription at one independently configured observation point. */
final class MqttObservationClient implements AutoCloseable {
  private static final long OPERATION_TIMEOUT_MS = 15_000;
  private final String name;
  private final MqttAsyncClient client;
  private final AtomicReference<String> failure = new AtomicReference<>();
  private final ReplayValidationEngine engine;
  private final AtomicLong lastReceived = new AtomicLong();

  MqttObservationClient(String name, String url, ReplayValidationEngine engine) throws Exception {
    this.name = name;
    this.engine = engine;
    this.client = new MqttAsyncClient(url, "maps-mqtt-mesh-observer-" + UUID.randomUUID(), new MemoryPersistence());
    client.setCallback(new MqttCallback() {
      @Override
      public void connectionLost(Throwable cause) {
        String message = cause == null ? "Connection lost" : "Connection lost: " + cause.getMessage();
        if (failure.compareAndSet(null, message)) {
          System.err.println(name + ": " + message);
        }
      }

      @Override
      public void messageArrived(String topic, MqttMessage message) {
        lastReceived.set(System.nanoTime());
        MqttObservationClient.this.engine.received(MqttObservationClient.this.name, topic, message.getPayload());
      }

      @Override
      public void deliveryComplete(IMqttDeliveryToken token) {
      }
    });
  }

  void start(List<String> filters) throws Exception {
    MqttConnectOptions options = new MqttConnectOptions();
    options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
    options.setCleanSession(true);
    options.setAutomaticReconnect(false);
    client.connect(options).waitForCompletion(OPERATION_TIMEOUT_MS);
    for (String filter : filters) {
      IMqttToken token = client.subscribe(filter, 1);
      token.waitForCompletion(OPERATION_TIMEOUT_MS);
      int[] granted = token.getGrantedQos();
      if (granted == null || granted.length != 1 || granted[0] < 0 || granted[0] > 1) {
        failure.set("Subscription rejected: " + filter);
        throw new IllegalStateException(name + ": " + failure.get());
      }
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
    try {
      if (client.isConnected()) {
        client.disconnect().waitForCompletion(OPERATION_TIMEOUT_MS);
      }
    } finally {
      client.close();
    }
  }
}
