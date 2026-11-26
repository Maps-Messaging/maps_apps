package io.mapsmessaging.tools.mqttreplay;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public final class MqttReplayPublisher implements AutoCloseable {
  private final PublishingPool pool;
  private final String broker;
  private final boolean sessionPerTopic;
  private final String sessionPrefix;
  private final long idleNanos;
  private final Map<String, PublishingSession> clients = new HashMap<>();
  private final ScheduledExecutorService cleanup;

  MqttReplayPublisher(String broker, boolean sessionPerTopic, String sessionPrefix, long sessionIdleMillis) {
    this(broker, sessionPerTopic, sessionPrefix, sessionIdleMillis, 0, 100, event -> {});
  }

  MqttReplayPublisher(String broker, boolean sessionPerTopic, String sessionPrefix, long sessionIdleMillis,
                      int sessions, int queueDepth, Consumer<ReplayEvent> completed) {
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
    pool = sessions == 0 ? null : new PublishingPool(sessions, queueDepth, index -> {
      MqttClient client = connectClient(true);
      return new PublishingPool.Session() {
        public void publish(ReplayEvent event) throws Exception {
          send(client, event);
        }

        public void close() throws Exception {
          closeClient(client);
        }
      };
    }, completed);
    if (pool == null) {
      cleanup.scheduleWithFixedDelay(this::closeIdleSessions, interval, interval, TimeUnit.MILLISECONDS);
    }
  }

  void publish(ReplayEvent event) throws Exception {
    if (pool != null) {
      pool.publish(event);
    } else {
      publishDynamic(event);
    }
  }

  void drain() throws Exception {
    if (pool != null) {
      pool.drain();
    }
  }

  long completedCount() {
    return pool == null ? 0 : pool.completedCount();
  }

  private synchronized void publishDynamic(ReplayEvent event) throws Exception {
    String key = SessionKeyResolver.resolve(event.topic(), sessionPerTopic, sessionPrefix);
    PublishingSession session = clients.get(key);
    if (session == null) {
      MqttClient client = connectClient(false);
      session = new PublishingSession(client);
      clients.put(key, session);
    }
    send(session.client, event);
    session.lastPublishedNanos = System.nanoTime();
  }

  private MqttClient connectClient(boolean pooled) throws Exception {
    MqttClient client = new MqttClient(broker, "maps-mqtt-replay-" + UUID.randomUUID(), new MemoryPersistence());
    if (pooled) {
      client.setTimeToWait(30_000L);
    }
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
    return client;
  }

  private static void send(MqttClient client, ReplayEvent event) throws Exception {
    MqttMessage message = new MqttMessage(event.payload());
    message.setQos(event.qos());
    message.setRetained(event.retained());
    client.publish(event.topic(), message);
  }

  synchronized int sessionCount() {
    return pool == null ? clients.size() : pool.sessionCount();
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
    if (pool != null) {
      pool.close();
      return;
    }
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
