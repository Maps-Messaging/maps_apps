package io.mapsmessaging.tools.valuelogger;

import com.google.gson.JsonObject;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.StandardCharsets;

public class MapsValueLogger {

  private final MapsValueLoggerArguments arguments;
  private final JsonLogRecordBuilder logRecordBuilder;
  private final LogWriter logWriter;
  private final MapsMqttClient mapsMqttClient;

  public MapsValueLogger(MapsValueLoggerArguments arguments) {
    this.arguments = arguments;
    this.logRecordBuilder = new JsonLogRecordBuilder();
    this.logWriter = LogWriterFactory.create(arguments);
    this.mapsMqttClient = new MapsMqttClient(arguments, this::handleMessage);
  }

  public void start() throws Exception {
    logWriter.open();
    mapsMqttClient.start();

    System.err.println(
        "Value logger started, format="
            + arguments.getOutputFormat());
  }

  public void stop() {
    try {
      mapsMqttClient.stop();
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