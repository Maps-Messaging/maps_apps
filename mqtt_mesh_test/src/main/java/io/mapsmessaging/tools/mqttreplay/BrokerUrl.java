package io.mapsmessaging.tools.mqttreplay;

import java.net.URI;
import java.net.URISyntaxException;

final class BrokerUrl {
  private BrokerUrl() {
  }

  static String normalize(String value) {
    try {
      URI uri = new URI(value);
      String path = uri.getRawPath();
      if (path == null || path.isEmpty()) {
        return value;
      }
      if (!"/".equals(path)) {
        throw new IllegalArgumentException("MQTT broker URL must not contain a path: " + value);
      }
      // Remove only a trailing root slash; do not rewrite escaped URI components.
      if (!value.endsWith("/")) {
        throw new IllegalArgumentException("MQTT broker URL must not contain a path: " + value);
      }
      return value.substring(0, value.length() - 1);
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("Invalid MQTT broker URL: " + value, exception);
    }
  }
}
