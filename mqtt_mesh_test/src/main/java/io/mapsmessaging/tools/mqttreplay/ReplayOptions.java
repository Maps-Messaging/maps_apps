package io.mapsmessaging.tools.mqttreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record ReplayOptions(Path directory, String broker, double speed, boolean sessionPerTopic,
                            String sessionPrefix, int qos, List<String> observeUrls,
                            String observeTopic, long drainMillis, Path report, boolean noDelay,
                            long sessionIdleMillis, int sessions, int queueDepth, int subscriberSessions) {
  public ReplayOptions {
    observeUrls = List.copyOf(observeUrls);
    if (subscriberSessions < 1) {
      throw new IllegalArgumentException("--subscriber-sessions must be positive");
    }
  }

  public ReplayOptions(Path directory, String broker, double speed, boolean sessionPerTopic,
                       String sessionPrefix, int qos, List<String> observeUrls,
                       String observeTopic, long drainMillis, Path report, boolean noDelay,
                       long sessionIdleMillis) {
    this(directory, broker, speed, sessionPerTopic, sessionPrefix, qos, observeUrls,
        observeTopic, drainMillis, report, noDelay, sessionIdleMillis, 0, 100);
  }

  public ReplayOptions(Path directory, String broker, double speed, boolean sessionPerTopic,
                       String sessionPrefix, int qos, List<String> observeUrls,
                       String observeTopic, long drainMillis, Path report, boolean noDelay,
                       long sessionIdleMillis, int sessions, int queueDepth) {
    this(directory, broker, speed, sessionPerTopic, sessionPrefix, qos, observeUrls,
        observeTopic, drainMillis, report, noDelay, sessionIdleMillis, sessions, queueDepth, 1);
  }

  public static ReplayOptions parse(String[] arguments) {
    Path directory = null;
    String broker = null;
    double speed = 1.0;
    boolean noDelay = false;
    long sessionIdleMillis = 60000;
    boolean sessionPerTopic = false;
    String sessionPrefix = null;
    int qos = 0;
    int sessions = 0;
    int queueDepth = 100;
    int subscriberSessions = 1;
    List<String> observeUrls = new ArrayList<>();
    String observeTopic = "#";
    long drainMillis = 5000;
    Path report = null;

    for (int i = 0; i < arguments.length; i++) {
      String option = arguments[i];
      switch (option) {
        case "--dir" -> directory = Path.of(value(arguments, ++i, option));
        case "--broker" -> broker = BrokerUrl.normalize(value(arguments, ++i, option));
        case "--speed" -> speed = Double.parseDouble(value(arguments, ++i, option));
        case "--no-delay" -> noDelay = true;
        case "--session-per-topic" -> sessionPerTopic = true;
        case "--session-prefix" -> sessionPrefix = value(arguments, ++i, option);
        case "--session-idle-ms" -> sessionIdleMillis = Long.parseLong(value(arguments, ++i, option));
        case "--sessions" -> {
          sessions = Integer.parseInt(value(arguments, ++i, option));
          if (sessions <= 0) {
            throw new IllegalArgumentException("--sessions must be positive");
          }
        }
        case "--subscriber-sessions" -> subscriberSessions = Integer.parseInt(value(arguments, ++i, option));
        case "--queue-depth" -> queueDepth = Integer.parseInt(value(arguments, ++i, option));
        case "--qos" -> qos = Integer.parseInt(value(arguments, ++i, option));
        case "--observe" -> observeUrls.add(BrokerUrl.normalize(value(arguments, ++i, option)));
        case "--observe-topic" -> observeTopic = value(arguments, ++i, option);
        case "--drain-ms" -> drainMillis = Long.parseLong(value(arguments, ++i, option));
        case "--report" -> report = Path.of(value(arguments, ++i, option));
        default -> throw new IllegalArgumentException("Unknown argument: " + option);
      }
    }
    if (directory == null || !Files.isDirectory(directory)) {
      throw new IllegalArgumentException("--dir must name an existing directory");
    }
    if (broker == null || broker.isBlank()) {
      throw new IllegalArgumentException("--broker is required");
    }
    if (!Double.isFinite(speed) || speed <= 0) {
      throw new IllegalArgumentException("--speed must be finite and positive");
    }
    if (sessionPerTopic && sessionPrefix != null) {
      throw new IllegalArgumentException("--session-per-topic and --session-prefix are mutually exclusive");
    }
    if (sessionPrefix != null) {
      if (sessionPrefix.isBlank() || sessionPrefix.equals("/") || sessionPrefix.contains("//") || sessionPrefix.contains("+") || sessionPrefix.contains("#")) {
        throw new IllegalArgumentException("--session-prefix must be a valid MQTT topic prefix");
      }
      while (sessionPrefix.endsWith("/")) {
        sessionPrefix = sessionPrefix.substring(0, sessionPrefix.length() - 1);
      }
    }
    if (queueDepth <= 0) {
      throw new IllegalArgumentException("--queue-depth must be positive");
    }
    if (sessions > 0 && (sessionPerTopic || sessionPrefix != null)) {
      throw new IllegalArgumentException("--sessions cannot be combined with dynamic session modes");
    }
    if (qos < 0 || qos > 2) {
      throw new IllegalArgumentException("--qos must be 0, 1 or 2");
    }
    if (sessionIdleMillis <= 0) {
      throw new IllegalArgumentException("--session-idle-ms must be positive");
    }
    if (drainMillis < 0) {
      throw new IllegalArgumentException("--drain-ms must be non-negative");
    }
    if (observeTopic.isBlank()) {
      throw new IllegalArgumentException("--observe-topic must not be blank");
    }
    return new ReplayOptions(directory, broker, speed, sessionPerTopic, sessionPrefix, qos,
        observeUrls, observeTopic, drainMillis, report, noDelay, sessionIdleMillis, sessions, queueDepth, subscriberSessions);
  }

  private static String value(String[] arguments, int index, String option) {
    if (index >= arguments.length || arguments[index].isBlank()) {
      throw new IllegalArgumentException("Missing value for " + option);
    }
    return arguments[index];
  }
}
