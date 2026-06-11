package io.mapsmessaging.tools.valuelogger;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MapsMqttClient {

  private final MapsValueLoggerArguments arguments;
  private final MqttMessageHandler messageHandler;
  private final ExecutorService reconnectExecutor;
  private final AtomicBoolean running;
  private final AtomicBoolean reconnecting;

  private MqttClient mqttClient;

  public MapsMqttClient(
      MapsValueLoggerArguments arguments,
      MqttMessageHandler messageHandler) {
    this.arguments = arguments;
    this.messageHandler = messageHandler;
    this.reconnectExecutor = Executors.newSingleThreadExecutor();
    this.running = new AtomicBoolean(false);
    this.reconnecting = new AtomicBoolean(false);
  }

  public void start() throws Exception {
    running.set(true);
    createAndConnect();
  }

  public void stop() {
    running.set(false);

    try {
      reconnectExecutor.shutdownNow();
    } catch (Exception ignored) {
    }

    destroyClient();
  }

  private synchronized void createAndConnect() throws Exception {
    destroyClient();

    String clientId = "maps-value-logger-" + UUID.randomUUID() + "?Transformation=Message-JSON";

    mqttClient = new MqttClient(
        arguments.getUrl(),
        clientId,
        new MemoryPersistence());

    mqttClient.setCallback(new MqttCallbackExtended() {

      @Override
      public void connectionLost(Throwable cause) {
        if (cause != null) {
          System.err.println("MQTT connection lost: " + cause.getMessage());
        } else {
          System.err.println("MQTT connection lost");
        }

        scheduleReconnect();
      }

      @Override
      public void messageArrived(String topic, MqttMessage message) {
        messageHandler.onMessage(topic, message);
      }

      @Override
      public void deliveryComplete(IMqttDeliveryToken token) {
      }

      @Override
      public void connectComplete(boolean reconnect, String serverURI) {
        System.err.println("MQTT connected: " + serverURI + ", reconnect=" + reconnect);
      }
    });

    MqttConnectOptions connectionOptions = new MqttConnectOptions();
    connectionOptions.setAutomaticReconnect(false);
    connectionOptions.setCleanSession(true);
    connectionOptions.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);

    mqttClient.connect(connectionOptions);
    mqttClient.subscribe(arguments.getTopic(), arguments.getQos());

    System.err.println(
        "Subscribed to "
            + arguments.getTopic()
            + " using "
            + arguments.getUrl());
  }

  private void scheduleReconnect() {
    if (!running.get()) {
      return;
    }

    if (!reconnecting.compareAndSet(false, true)) {
      return;
    }

    reconnectExecutor.submit(() -> {
      while (running.get()) {
        try {
          createAndConnect();
          reconnecting.set(false);
          return;
        } catch (Exception exception) {
          System.err.println("MQTT reconnect failed: " + exception.getMessage());
          sleepBeforeRetry();
        }
      }

      reconnecting.set(false);
    });
  }

  private synchronized void destroyClient() {
    try {
      if (mqttClient != null && mqttClient.isConnected()) {
        mqttClient.disconnect();
      }
    } catch (Exception ignored) {
    }

    try {
      if (mqttClient != null) {
        mqttClient.close();
      }
    } catch (Exception ignored) {
    }

    mqttClient = null;
  }

  private void sleepBeforeRetry() {
    try {
      Thread.sleep(1000);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}