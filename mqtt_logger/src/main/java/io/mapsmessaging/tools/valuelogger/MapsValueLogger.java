package io.mapsmessaging.tools.valuelogger;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

public class MapsValueLogger {

  private final MapsValueLoggerArguments arguments;
  private final JsonLogRecordBuilder logRecordBuilder;
  private final LogWriter logWriter;

  private MqttClient mqttClient;

  public MapsValueLogger(MapsValueLoggerArguments arguments) {
    this.arguments = arguments;
    this.logRecordBuilder = new JsonLogRecordBuilder();
    this.logWriter = LogWriterFactory.create(arguments);
  }

  public void start() throws Exception {
    String clientId = "maps-value-logger-" + UUID.randomUUID();

    mqttClient = new MqttClient(
        arguments.getUrl(),
        clientId,
        new MemoryPersistence());

    MqttConnectionOptions connectionOptions = new MqttConnectionOptions();
    connectionOptions.setAutomaticReconnect(true);
    connectionOptions.setCleanStart(true);

    mqttClient.setCallback(new MqttCallback() {

      @Override
      public void disconnected(MqttDisconnectResponse disconnectResponse) {
        System.err.println("MQTT disconnected: " + disconnectResponse.getReasonString());
      }

      @Override
      public void mqttErrorOccurred(MqttException exception) {
        System.err.println("MQTT error: " + exception.getMessage());
      }

      @Override
      public void messageArrived(String topic, MqttMessage message) {
        handleMessage(topic, message);
      }

      @Override
      public void deliveryComplete(IMqttToken token) {
      }

      @Override
      public void connectComplete(boolean reconnect, String serverURI) {
        System.err.println("MQTT connected: " + serverURI + ", reconnect=" + reconnect);
      }

      @Override
      public void authPacketArrived(int reasonCode, MqttProperties properties) {
      }
    });

    logWriter.open();

    mqttClient.connect(connectionOptions);
    mqttClient.subscribe(arguments.getTopic(), arguments.getQos());

    System.err.println(
        "Subscribed to "
            + arguments.getTopic()
            + " using "
            + arguments.getUrl()
            + ", format="
            + arguments.getOutputFormat());
  }

  public void stop() {
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

    try {
      logWriter.close();
    } catch (Exception ignored) {
    }
  }

  private void handleMessage(String topic, MqttMessage message) {
    try {
      String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
      JsonObject logRecord = logRecordBuilder.build(topic, payload);

      logWriter.write(logRecord);
    } catch (Exception exception) {
      System.err.println("Failed to process message from topic " + topic + ": " + exception.getMessage());
    }
  }
}