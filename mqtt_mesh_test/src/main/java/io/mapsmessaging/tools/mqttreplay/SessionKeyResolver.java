package io.mapsmessaging.tools.mqttreplay;

final class SessionKeyResolver {
  private SessionKeyResolver() {
  }

  static String resolve(String topic, boolean sessionPerTopic, String sessionPrefix) {
    if (sessionPrefix == null) {
      return sessionPerTopic ? topic : "";
    }
    String prefix = sessionPrefix + "/";
    if (!topic.startsWith(prefix)) {
      return "";
    }
    int childStart = prefix.length();
    int childEnd = topic.indexOf('/', childStart);
    if (childEnd <= childStart || childEnd == topic.length() - 1) {
      return "";
    }
    return topic.substring(0, childEnd);
  }
}
