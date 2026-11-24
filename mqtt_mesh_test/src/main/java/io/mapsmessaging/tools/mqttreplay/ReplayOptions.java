package io.mapsmessaging.tools.mqttreplay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record ReplayOptions(Path directory, String broker, double speed, boolean sessionPerTopic,
                            String sessionPrefix, int qos, List<String> observeUrls,
                            String observeTopic, long drainMillis, Path report) {
  public ReplayOptions {
    observeUrls = List.copyOf(observeUrls);
  }

  public static ReplayOptions parse(String[] arguments) {
    Path directory = null;
    String broker = null;
    double speed = 1.0;
    boolean sessionPerTopic = false;
    String sessionPrefix = null;
    int qos = 1;
    List<String> observeUrls = new ArrayList<>();
    String observeTopic = "#";
    long drainMillis = 5000;
    Path report = null;

    for (int i = 0; i < arguments.length; i++) {
      String option = arguments[i];
      switch (option) {
        case "--dir" -> directory = Path.of(value(arguments, ++i, option));
        case "--broker" -> broker = value(arguments, ++i, option);
        case "--speed" -> speed = Double.parseDouble(value(arguments, ++i, option));
        case "--session-per-topic" -> sessionPerTopic = true;
        case "--session-prefix" -> sessionPrefix = value(arguments, ++i, option);
        case "--qos" -> qos = Integer.parseInt(value(arguments, ++i, option));
        case "--observe" -> observeUrls.add(value(arguments, ++i, option));
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
      if (!sessionPrefix.startsWith("/") || sessionPrefix.equals("/") || sessionPrefix.contains("//") || sessionPrefix.contains("+") || sessionPrefix.contains("#")) {
        throw new IllegalArgumentException("--session-prefix must be an absolute MQTT topic path");
      }
      while (sessionPrefix.endsWith("/")) {
        sessionPrefix = sessionPrefix.substring(0, sessionPrefix.length() - 1);
      }
    }
    if (qos < 0 || qos > 2) {
      throw new IllegalArgumentException("--qos must be 0, 1 or 2");
    }
    if (drainMillis < 0) {
      throw new IllegalArgumentException("--drain-ms must be non-negative");
    }
    if (observeTopic.isBlank()) {
      throw new IllegalArgumentException("--observe-topic must not be blank");
    }
    return new ReplayOptions(directory, broker, speed, sessionPerTopic, sessionPrefix, qos,
        observeUrls, observeTopic, drainMillis, report);
  }

  private static String value(String[] arguments, int index, String option) {
    if (index >= arguments.length || arguments[index].isBlank()) {
      throw new IllegalArgumentException("Missing value for " + option);
    }
    return arguments[index];
  }
}
