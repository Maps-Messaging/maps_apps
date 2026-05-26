package io.mapsmessaging.tools.valuelogger;

import lombok.Getter;

@Getter
public class MapsValueLoggerArguments {

  private final String url;
  private final String topic;
  private final int qos;
  private final String outputFileName;

  private MapsValueLoggerArguments(
      String url,
      String topic,
      int qos,
      String outputFileName) {
    this.url = url;
    this.topic = topic;
    this.qos = qos;
    this.outputFileName = outputFileName;
  }

  public static MapsValueLoggerArguments parse(String[] args) {
    String url = null;
    String topic = null;
    Integer qos = null;
    String outputFileName = null;

    for (int index = 0; index < args.length; index++) {
      String argument = args[index];

      switch (argument) {
        case "--url":
          url = readRequiredValue(args, ++index, "--url");
          break;

        case "--topic":
          topic = readRequiredValue(args, ++index, "--topic");
          break;

        case "--qos":
          String qosText = readRequiredValue(args, ++index, "--qos");
          qos = parseQos(qosText);
          break;

        case "--output":
          outputFileName = readRequiredValue(args, ++index, "--output");
          break;

        default:
          throw new IllegalArgumentException("Unknown argument: " + argument);
      }
    }

    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("Missing required argument: --url");
    }

    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("Missing required argument: --topic");
    }

    if (qos == null) {
      throw new IllegalArgumentException("Missing required argument: --qos");
    }

    return new MapsValueLoggerArguments(url, topic, qos, outputFileName);
  }

  public static void printUsage() {
    System.err.println("Usage:");
    System.err.println("  maps-value-logger --url <url> --topic <topic> --qos <0|1|2> [--output <file>]");
    System.err.println();
    System.err.println("Example:");
    System.err.println("  maps-value-logger --url tcp://localhost:1883 --topic \"/#\" --qos 1 --output maps-values.ndjson");
  }

  private static String readRequiredValue(String[] args, int index, String name) {
    if (index >= args.length) {
      throw new IllegalArgumentException("Missing value for argument: " + name);
    }

    String value = args[index];

    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Blank value for argument: " + name);
    }

    return value;
  }

  private static int parseQos(String qosText) {
    try {
      int qos = Integer.parseInt(qosText);

      if (qos < 0 || qos > 2) {
        throw new IllegalArgumentException("--qos must be 0, 1, or 2");
      }

      return qos;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("--qos must be 0, 1, or 2", exception);
    }
  }
}