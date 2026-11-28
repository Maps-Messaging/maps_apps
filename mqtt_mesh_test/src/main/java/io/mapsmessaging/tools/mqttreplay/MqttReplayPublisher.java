package io.mapsmessaging.tools.mqttreplay;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MqttReplayPublisher implements AutoCloseable {
  private final PublishingPool pool;
  private final String broker;
  private final String mqttVersion;
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
    this(broker, sessionPerTopic, sessionPrefix, sessionIdleMillis, sessions, queueDepth, completed, "3.1.1");
  }

  MqttReplayPublisher(String broker, boolean sessionPerTopic, String sessionPrefix, long sessionIdleMillis,
                      int sessions, int queueDepth, Consumer<ReplayEvent> completed, String mqttVersion) {
    this.broker = broker;
    this.mqttVersion = mqttVersion;
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
      MqttWireClient client = connectClient(true);
      return new PublishingPool.Session() {
        public void publish(ReplayEvent event) throws Exception {
          send(client, event);
        }

        public String publicationDiagnostics() {
          return client.publicationDiagnostics();
        }

        public void close() throws Exception {
          closeClient(client);
        }
      };
    }, completed, mqttVersion);
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
      MqttWireClient client = connectClient(false);
      session = new PublishingSession(client);
      clients.put(key, session);
    }
    send(session.client, event);
    session.lastPublishedNanos = System.nanoTime();
  }

  private MqttWireClient connectClient(boolean pooled) throws Exception {
    MqttWireClient client = MqttWireClient.create(mqttVersion, broker, "maps-mqtt-replay-" + UUID.randomUUID(),
        pooled ? 30_000L : -1L, null, null);
    try {
      client.connect();
    } catch (Exception exception) {
      try {
        client.close();
      } catch (Exception cleanup) {
        exception.addSuppressed(cleanup);
      }
      throw new IllegalStateException("MQTT " + mqttVersion + " publishing connection failed: " + broker, exception);
    }
    return client;
  }

  private void send(MqttWireClient client, ReplayEvent event) throws Exception {
    try {
      client.publish(event);
    } catch (Exception exception) {
      System.err.println("Publisher failure before close: " + client.publicationDiagnostics());
      throw new IllegalStateException("MQTT " + mqttVersion + " publish failed: " + event.topic(), exception);
    }
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

  private static void closeClient(MqttWireClient client) throws Exception {
    client.close();
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
    private final MqttWireClient client;
    private long lastPublishedNanos = System.nanoTime();

    private PublishingSession(MqttWireClient client) {
      this.client = client;
    }
  }
}
