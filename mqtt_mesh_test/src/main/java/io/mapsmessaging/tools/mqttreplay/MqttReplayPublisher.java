package io.mapsmessaging.tools.mqttreplay;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public final class MqttReplayPublisher implements AutoCloseable {
  private final String broker;
  private final boolean sessionPerTopic;
  private final String sessionPrefix;
  private final Map<String, MqttClient> clients = new HashMap<>();

  MqttReplayPublisher(String broker, boolean sessionPerTopic, String sessionPrefix) {
    this.broker = broker;
    this.sessionPerTopic = sessionPerTopic;
    this.sessionPrefix = sessionPrefix;
  }

  void publish(ReplayEvent event) throws Exception {
    String key = SessionKeyResolver.resolve(event.topic(), sessionPerTopic, sessionPrefix);
    MqttClient client = clients.get(key);
    if (client == null) {
      client = new MqttClient(broker, "maps-mqtt-replay-" + UUID.randomUUID(), new MemoryPersistence());
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
      clients.put(key, client);
    }
    MqttMessage message = new MqttMessage(event.payload());
    message.setQos(event.qos());
    message.setRetained(event.retained());
    client.publish(event.topic(), message);
  }

  int sessionCount() {
    return clients.size();
  }

  @Override
  public void close() throws Exception {
    Exception error = null;
    for (MqttClient client : clients.values()) {
      try {
        if (client.isConnected()) {
          client.disconnect();
        }
        client.close();
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
}
