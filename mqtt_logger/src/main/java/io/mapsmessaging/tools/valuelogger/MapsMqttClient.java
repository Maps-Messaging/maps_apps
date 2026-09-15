package io.mapsmessaging.tools.valuelogger;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MapsMqttClient {

  static final int KEEP_ALIVE_SECONDS = 30;
  static final int CONNECTION_TIMEOUT_SECONDS = 10;
  static final long OPERATION_TIMEOUT_MILLIS = 15_000L;
  static final long HEALTH_CHECK_INITIAL_DELAY_SECONDS = 5L;
  static final long HEALTH_CHECK_PERIOD_SECONDS = 5L;
  static final long RECONNECT_RETRY_MILLIS = 1_000L;
  static final long FORCED_DISCONNECT_TIMEOUT_MILLIS = 1_000L;

  @FunctionalInterface
  interface ClientFactory {
    MqttAsyncClient create(String url, String clientId) throws MqttException;
  }

  private final MapsValueLoggerArguments arguments;
  private final MqttMessageHandler messageHandler;
  private final ClientFactory clientFactory;
  private final ExecutorService reconnectExecutor;
  private final ScheduledExecutorService healthExecutor;
  private final AtomicBoolean running;
  private final AtomicBoolean reconnecting;

  private volatile MqttAsyncClient mqttClient;
  private volatile ScheduledFuture<?> healthTask;

  public MapsMqttClient(
      MapsValueLoggerArguments arguments,
      MqttMessageHandler messageHandler) {
    this(
        arguments,
        messageHandler,
        (url, clientId) -> new MqttAsyncClient(url, clientId, new MemoryPersistence()),
        Executors.newSingleThreadExecutor(namedThreadFactory("maps-logger-mqtt-reconnect")),
        Executors.newSingleThreadScheduledExecutor(namedThreadFactory("maps-logger-mqtt-health")));
  }

  MapsMqttClient(
      MapsValueLoggerArguments arguments,
      MqttMessageHandler messageHandler,
      ClientFactory clientFactory,
      ExecutorService reconnectExecutor,
      ScheduledExecutorService healthExecutor) {
    this.arguments = arguments;
    this.messageHandler = messageHandler;
    this.clientFactory = clientFactory;
    this.reconnectExecutor = reconnectExecutor;
    this.healthExecutor = healthExecutor;
    this.running = new AtomicBoolean(false);
    this.reconnecting = new AtomicBoolean(false);
  }

  public void start() throws Exception {
    if (!running.compareAndSet(false, true)) {
      return;
    }

    try {
      createAndConnect();
      healthTask = healthExecutor.scheduleWithFixedDelay(
          this::checkConnectionHealth,
          HEALTH_CHECK_INITIAL_DELAY_SECONDS,
          HEALTH_CHECK_PERIOD_SECONDS,
          TimeUnit.SECONDS);
    } catch (Exception exception) {
      running.set(false);
      destroyClient();
      throw exception;
    }
  }

  public void stop() {
    running.set(false);

    ScheduledFuture<?> task = healthTask;
    if (task != null) {
      task.cancel(true);
    }

    try {
      healthExecutor.shutdownNow();
    } catch (Exception ignored) {
    }

    try {
      reconnectExecutor.shutdownNow();
    } catch (Exception ignored) {
    }

    destroyClient();
  }

  static MqttConnectOptions createConnectionOptions() {
    MqttConnectOptions connectionOptions = new MqttConnectOptions();
    connectionOptions.setAutomaticReconnect(false);
    connectionOptions.setCleanSession(true);
    connectionOptions.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
    connectionOptions.setKeepAliveInterval(KEEP_ALIVE_SECONDS);
    connectionOptions.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);
    return connectionOptions;
  }

  private synchronized void createAndConnect() throws Exception {
    if (!running.get()) {
      return;
    }

    destroyClient();

    String clientId = "maps-value-logger-" + UUID.randomUUID() + "?Transformation=Message-JSON";
    MqttAsyncClient client = clientFactory.create(arguments.getUrl(), clientId);
    client.setManualAcks(true);
    client.setCallback(new MqttCallbackExtended() {

      @Override
      public void connectionLost(Throwable cause) {
        if (cause != null) {
          System.err.println("MQTT connection lost: " + cause.getMessage());
        } else {
          System.err.println("MQTT connection lost");
        }

        if (client == mqttClient) {
          scheduleReconnect("connection lost callback");
        }
      }

      @Override
      public void messageArrived(String topic, MqttMessage message) {
        int messageId = message.getId();
        int qos = message.getQos();

        messageHandler.onMessage(
            topic,
            message,
            () -> acknowledge(client, messageId, qos));
      }

      @Override
      public void deliveryComplete(IMqttDeliveryToken token) {
      }

      @Override
      public void connectComplete(boolean reconnect, String serverURI) {
        System.err.println("MQTT connected: " + serverURI + ", reconnect=" + reconnect);
      }
    });

    mqttClient = client;

    try {
      IMqttToken connectToken = client.connect(createConnectionOptions());
      connectToken.waitForCompletion(OPERATION_TIMEOUT_MILLIS);

      IMqttToken subscribeToken = client.subscribe(arguments.getTopic(), arguments.getQos());
      subscribeToken.waitForCompletion(OPERATION_TIMEOUT_MILLIS);

      System.err.println(
          "Subscribed to "
              + arguments.getTopic()
              + " using "
              + arguments.getUrl()
              + " (keepalive="
              + KEEP_ALIVE_SECONDS
              + "s)");
    } catch (Exception exception) {
      destroyClient();
      throw exception;
    }
  }

  private void acknowledge(MqttAsyncClient client, int messageId, int qos) {
    if (qos <= 0) {
      return;
    }

    try {
      client.messageArrivedComplete(messageId, qos);
    } catch (MqttException exception) {
      System.err.println(
          "MQTT acknowledgement failed for message "
              + messageId
              + ": "
              + exception.getMessage());

      if (client == mqttClient) {
        scheduleReconnect("message acknowledgement failed");
      }
    }
  }

  private void checkConnectionHealth() {
    if (!running.get()) {
      return;
    }

    MqttAsyncClient client = mqttClient;

    if ((client == null || !client.isConnected()) && client == mqttClient) {
      scheduleReconnect("health check found no active MQTT connection");
    }
  }

  private void scheduleReconnect(String reason) {
    if (!running.get()) {
      return;
    }

    if (!reconnecting.compareAndSet(false, true)) {
      return;
    }

    System.err.println("MQTT reconnect scheduled: " + reason);

    reconnectExecutor.submit(() -> {
      try {
        while (running.get()) {
          try {
            createAndConnect();
            return;
          } catch (Exception exception) {
            System.err.println("MQTT reconnect failed: " + exception.getMessage());
            sleepBeforeRetry();
          }
        }
      } finally {
        reconnecting.set(false);

        if (running.get()) {
          MqttAsyncClient client = mqttClient;
          if (client == null || !client.isConnected()) {
            scheduleReconnect("connection still unavailable after reconnect loop");
          }
        }
      }
    });
  }

  private synchronized void destroyClient() {
    MqttAsyncClient client = mqttClient;
    mqttClient = null;

    if (client == null) {
      return;
    }

    try {
      if (client.isConnected()) {
        client.disconnectForcibly(0, FORCED_DISCONNECT_TIMEOUT_MILLIS, false);
      }
    } catch (Exception ignored) {
    }

    try {
      client.close(true);
    } catch (Exception ignored) {
    }
  }

  private void sleepBeforeRetry() {
    try {
      Thread.sleep(RECONNECT_RETRY_MILLIS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static ThreadFactory namedThreadFactory(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(false);
      return thread;
    };
  }
}
