package io.mapsmessaging.tools.valuelogger;

import org.eclipse.paho.client.mqttv3.MqttMessage;

@FunctionalInterface
public interface MqttMessageHandler {

  void onMessage(String topic, MqttMessage message);
}