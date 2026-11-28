package io.mapsmessaging.tools.mqttreplay;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Only the MQTT operations used by replay and observation. */
interface MqttWireClient extends AutoCloseable {
  static void validateVersion(String version) {
    if (!"3.1".equals(version) && !"3.1.1".equals(version) && !"5.0".equals(version)) {
      throw new IllegalArgumentException("--mqtt-version must be 3.1, 3.1.1 or 5.0");
    }
  }

  static MqttWireClient create(String version, String url, String id, long timeout,
                               BiConsumer<String, byte[]> received, Consumer<String> disconnected) throws Exception {
    validateVersion(version);
    // MQTT 3.1 requires client identifiers no longer than 23 characters.
    if ("3.1".equals(version) && id.length() > 23) {
      id = "maps" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 19);
    }
    try {
      return "5.0".equals(version) ? new Paho5WireClient(url, id, timeout, received, disconnected)
          : new Paho3WireClient(version, url, id, timeout, received, disconnected);
    } catch (Exception exception) {
      throw new IllegalStateException("MQTT " + version + " client creation failed for " + url, exception);
    }
  }

  static void requireSuback(String filter, int qos, int[] codes) {
    if (codes == null || codes.length != 1 || codes[0] < 0 || codes[0] > qos) {
      throw new IllegalStateException("Subscription rejected: " + filter + ", reason codes " + java.util.Arrays.toString(codes));
    }
  }

  void connect() throws Exception;
  void publish(ReplayEvent event) throws Exception;
  void subscribe(String filter, int qos) throws Exception;
  boolean isConnected();
  String publicationDiagnostics();
  void close() throws Exception;
}
