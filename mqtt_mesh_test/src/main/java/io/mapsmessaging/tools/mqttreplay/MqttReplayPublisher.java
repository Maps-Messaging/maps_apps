package io.mapsmessaging.tools.mqttreplay;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public final class MqttReplayPublisher implements AutoCloseable {
  private final String broker;
  private final boolean sessionPerTopic;
  private final String sessionPrefix;
  private final long idleNanos;
  private final Map<String, PublishingSession> clients = new HashMap<>();
  private final ScheduledExecutorService cleanup;

  MqttReplayPublisher(String broker, boolean sessionPerTopic, String sessionPrefix, long sessionIdleMillis) {
    this.broker = broker;
    this.sessionPerTopic = sessionPerTopic;
    this.sessionPrefix = sessionPrefix;
    this.idleNanos = TimeUnit.MILLISECONDS.toNanos(sessionIdleMillis);
    this.cleanup = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "mqtt-replay-session-cleanup");
      thread.setDaemon(true);
      return thread;
    });
    long interval = Math.max(1L, Math.min(5000L, sessionIdleMillis / 2));
    cleanup.scheduleWithFixedDelay(this::closeIdleSessions, interval, interval, TimeUnit.MILLISECONDS);
  }

  synchronized void publish(ReplayEvent event) throws Exception {
    String key = SessionKeyResolver.resolve(event.topic(), sessionPerTopic, sessionPrefix);
    PublishingSession session = clients.get(key);
    if (session == null) {
      MqttClient client = new MqttClient(broker, "maps-mqtt-replay-" + UUID.randomUUID(), new MemoryPersistence());
      try {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        client.connect(options);
      } catch (Exception exception) {
        client.close();
        throw exception;
      }
      session = new PublishingSession(client);
      clients.put(key, session);
    }
    MqttMessage message = new MqttMessage(event.payload());
    message.setQos(event.qos());
    message.setRetained(event.retained());
    session.client.publish(event.topic(), message);
    session.lastPublishedNanos = System.nanoTime();
  }

  synchronized int sessionCount() {
    return clients.size();
  }

  private synchronized void closeIdleSessions() {
    long now = System.nanoTime();
    Iterator<Map.Entry<String, PublishingSession>> iterator = clients.entrySet().iterator();
    while (iterator.hasNext()) {
      PublishingSession session = iterator.next().getValue();
      if (now - session.lastPublishedNanos < idleNanos) {
        continue;
      }
      try {
        closeClient(session.client);
        iterator.remove();
      } catch (Exception exception) {
        System.err.println("Unable to close idle MQTT publishing session: " + exception.getMessage());
      }
    }
  }

  private static void closeClient(MqttClient client) throws Exception {
    try {
      if (client.isConnected()) {
        client.disconnect();
      }
    } finally {
      client.close();
    }
  }

  @Override
  public synchronized void close() throws Exception {
    cleanup.shutdownNow();
    Exception error = null;
    for (PublishingSession session : clients.values()) {
      try {
        closeClient(session.client);
      } catch (Exception exception) {
        if (error == null) {
          error = exception;
        } else {
          error.addSuppressed(exception);
        }
      }
    }
    clients.clear();
    if (error != null) {
      throw error;
    }
  }

  private static final class PublishingSession {
    private final MqttClient client;
    private long lastPublishedNanos = System.nanoTime();

    private PublishingSession(MqttClient client) {
      this.client = client;
    }
  }
}
