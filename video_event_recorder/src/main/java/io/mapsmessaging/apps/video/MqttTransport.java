package io.mapsmessaging.apps.video;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

final class MqttTransport implements RecordingTransport {

  private final VideoRecorderConfig config;
  private final MqttAsyncClient client;
  private volatile Consumer<String> requestHandler;

  MqttTransport(VideoRecorderConfig config) throws Exception {
    this.config = config;
    this.client =
        new MqttAsyncClient(
            config.mqttUrl(),
            "maps-video-event-recorder-" + UUID.randomUUID(),
            new MemoryPersistence());
  }

  @Override
  public void start(Consumer<String> requestHandler) throws Exception {
    this.requestHandler = requestHandler;
    client.setCallback(
        new MqttCallbackExtended() {
          @Override
          public void connectComplete(boolean reconnect, String serverURI) {
            if (reconnect) {
              try {
                subscribe();
              } catch (Exception exception) {
                System.err.println("Unable to resubscribe to video recording requests: "
                    + exception.getMessage());
              }
            }
          }

          @Override
          public void connectionLost(Throwable cause) {
            if (cause != null) {
              System.err.println("Video recorder MQTT connection lost: " + cause.getMessage());
            }
          }

          @Override
          public void messageArrived(String topic, MqttMessage message) {
            Consumer<String> handler = MqttTransport.this.requestHandler;
            if (handler != null) {
              handler.accept(new String(message.getPayload(), StandardCharsets.UTF_8));
            }
          }

          @Override
          public void deliveryComplete(IMqttDeliveryToken token) {}
        });

    MqttConnectOptions options = new MqttConnectOptions();
    options.setAutomaticReconnect(true);
    options.setCleanSession(true);
    options.setConnectionTimeout(10);
    if (config.mqttUsername() != null) {
      options.setUserName(config.mqttUsername());
    }
    if (config.mqttPassword() != null) {
      options.setPassword(config.mqttPassword().toCharArray());
    }

    client.connect(options).waitForCompletion();
    subscribe();
  }

  @Override
  public void publish(String payload) throws Exception {
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
    message.setQos(config.qos());
    message.setRetained(false);
    client.publish(config.resultTopic(), message).waitForCompletion();
  }

  private void subscribe() throws Exception {
    client.subscribe(config.requestTopic(), config.qos()).waitForCompletion();
  }

  @Override
  public void close() throws Exception {
    if (client.isConnected()) {
      client.disconnect().waitForCompletion();
    }
    client.close();
  }
}
