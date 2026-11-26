package io.mapsmessaging.tools.mqttreplay;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

final class Paho3WireClient implements MqttWireClient {
  private final MqttAsyncClient client;
  private final String version;
  private final long timeout;

  Paho3WireClient(String version, String url, String id, long timeout,
                  BiConsumer<String, byte[]> received, Consumer<String> disconnected) throws Exception {
    this.version = version;
    this.timeout = timeout;
    client = new MqttAsyncClient(url, id, new MemoryPersistence());
    if (received != null) {
      client.setCallback(new MqttCallback() {
        public void connectionLost(Throwable cause) {
          disconnected.accept(cause == null ? "Connection lost" : "Connection lost: " + cause.getMessage());
        }

        public void messageArrived(String topic, MqttMessage message) {
          received.accept(topic, message.getPayload());
        }

        public void deliveryComplete(IMqttDeliveryToken token) {
        }
      });
    }
  }

  private void await(IMqttToken token) throws Exception {
    if (timeout < 0) {
      token.waitForCompletion();
    } else {
      token.waitForCompletion(timeout);
    }
  }

  public void connect() throws Exception {
    MqttConnectOptions options = new MqttConnectOptions();
    options.setMqttVersion("3.1".equals(version) ? MqttConnectOptions.MQTT_VERSION_3_1 : MqttConnectOptions.MQTT_VERSION_3_1_1);
    options.setCleanSession(true);
    options.setAutomaticReconnect(false);
    await(client.connect(options));
  }

  public void publish(ReplayEvent event) throws Exception {
    await(client.publish(event.topic(), event.payload(), event.qos(), event.retained()));
  }

  public void subscribe(String filter, int qos) throws Exception {
    IMqttToken token = client.subscribe(filter, qos);
    await(token);
    MqttWireClient.requireSuback(filter, qos, token.getGrantedQos());
  }

  public boolean isConnected() {
    return client.isConnected();
  }

  public void close() throws Exception {
    try {
      if (client.isConnected()) {
        await(client.disconnect());
      }
    } finally {
      client.close();
    }
  }
}
