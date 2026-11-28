package io.mapsmessaging.tools.mqttreplay;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

final class Paho5WireClient implements MqttWireClient {
  private final MqttAsyncClient client;
  private final long timeout;

  Paho5WireClient(String url, String id, long timeout,
                  BiConsumer<String, byte[]> received, Consumer<String> disconnected) throws Exception {
    this.timeout = timeout;
    client = new MqttAsyncClient(url, id, new MemoryPersistence());
    if (received != null) {
      client.setCallback(new MqttCallback() {
        public void disconnected(MqttDisconnectResponse response) {
          disconnected.accept("Connection lost: " + response);
        }

        public void mqttErrorOccurred(MqttException exception) {
          disconnected.accept("MQTT error: " + exception.getMessage());
        }

        public void messageArrived(String topic, MqttMessage message) {
          received.accept(topic, message.getPayload());
        }

        public void deliveryComplete(IMqttToken token) {
        }

        public void connectComplete(boolean reconnect, String serverURI) {
        }

        public void authPacketArrived(int reasonCode, MqttProperties properties) {
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
    MqttConnectionOptions options = new MqttConnectionOptions();
    options.setCleanStart(true);
    options.setSessionExpiryInterval(0L);
    options.setAutomaticReconnect(false);
    await(client.connect(options));
  }

  public void publish(ReplayEvent event) throws Exception {
    IMqttToken token = client.publish(event.topic(), event.payload(), event.qos(), event.retained());
    await(token);
    int[] reasons = token.getReasonCodes();
    if (reasons != null) {
      for (int reason : reasons) {
        if (reason >= 128) {
          throw new IllegalStateException("MQTT 5.0 publish rejected, reason code " + reason);
        }
      }
    }
  }

  public void subscribe(String filter, int qos) throws Exception {
    IMqttToken token = client.subscribe(filter, qos);
    await(token);
    MqttWireClient.requireSuback(filter, qos, token.getReasonCodes());
  }

  public String publicationDiagnostics() {
    try {
      IMqttToken[] tokens = client.getPendingTokens();
      String ids = java.util.Arrays.stream(tokens)
          .map(token -> token.getMessageId() + ":" + token.isComplete())
          .collect(java.util.stream.Collectors.joining(","));
      return "client=" + client.getClientId() + ", connected=" + client.isConnected()
          + ", pending=" + tokens.length + ", inFlight=" + client.getInFlightMessageCount()
          +  ", tokens(id:complete)=[" + ids + "]";
    } catch (RuntimeException failure) {
      return "diagnostics unavailable: " + failure;
    }
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
