package io.mapsmessaging.tools.valuelogger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;

public class MapsValueLogger {

  private final MapsValueLoggerArguments arguments;
  private final JsonLogRecordBuilder logRecordBuilder;
  private final NdjsonLogWriter logWriter;

  private MqttClient mqttClient;

  public MapsValueLogger(MapsValueLoggerArguments arguments) {
    this.arguments = arguments;
    this.logRecordBuilder = new JsonLogRecordBuilder();
    this.logWriter = new NdjsonLogWriter(arguments.getOutputFileName());
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

    logWriter.open();

    mqttClient.connect(connectionOptions);

    mqttClient.subscribe(
        arguments.getTopic(),
        arguments.getQos(),
        this::handleMessage);

    System.err.println("Subscribed to " + arguments.getTopic() + " using " + arguments.getUrl());
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
      String logRecord = logRecordBuilder.build(topic, payload);

      logWriter.write(logRecord);
    } catch (Exception exception) {
      System.err.println("Failed to process message from topic " + topic + ": " + exception.getMessage());
    }
  }
}