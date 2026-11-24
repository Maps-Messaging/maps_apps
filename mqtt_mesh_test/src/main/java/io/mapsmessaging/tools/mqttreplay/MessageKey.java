package io.mapsmessaging.tools.mqttreplay;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

record MessageKey(String topic, String payloadHash) {
  static MessageKey of(String topic, byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return new MessageKey(topic, HexFormat.of().formatHex(digest.digest(payload)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
