package io.mapsmessaging.tools.valuelogger;

import java.util.function.Function;
import lombok.Getter;

@Getter
public class MapsValueLoggerArguments {

  private final String url;
  private final String topic;
  private final int qos;
  private final String outputFileName;
  private final String outputDir;
  private final OutputFormat outputFormat;
  private final int diskWarnMb;

  private MapsValueLoggerArguments(
      String url,
      String topic,
      int qos,
      String outputFileName,
      String outputDir,
      OutputFormat outputFormat,
      int diskWarnMb) {
    this.url = url;
    this.topic = topic;
    this.qos = qos;
    this.outputFileName = outputFileName;
    this.outputDir = outputDir;
    this.outputFormat = outputFormat;
    this.diskWarnMb = diskWarnMb;
  }

  public static MapsValueLoggerArguments parse(String[] args) {
    return parse(args, System::getenv);
  }

  static MapsValueLoggerArguments parse(String[] args, Function<String, String> env) {
    String url = null;
    String topic = null;
    Integer qos = null;
    String outputFileName = null;
    String outputDir = null;
    OutputFormat requestedOutputFormat = null;
    Integer diskWarnMb = null;

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
          qos = parseQos(readRequiredValue(args, ++index, "--qos"));
          break;

        case "--output":
          outputFileName = readRequiredValue(args, ++index, "--output");
          break;

        case "--output-dir":
          outputDir = readRequiredValue(args, ++index, "--output-dir");
          break;

        case "--format":
          requestedOutputFormat = OutputFormat.parse(readRequiredValue(args, ++index, "--format"));
          break;

        case "--disk-warn-mb":
          diskWarnMb = parseDiskWarnMb(readRequiredValue(args, ++index, "--disk-warn-mb"));
          break;

        default:
          throw new IllegalArgumentException("Unknown argument: " + argument);
      }
    }

    // Env-var fallback, then built-in defaults
    if (url == null) {
      url = env.apply("MAPS_URL");
    }

    if (topic == null) {
      String envTopic = env.apply("MAPS_TOPIC");
      topic = (envTopic != null) ? envTopic : "/#";
    }

    if (qos == null) {
      String envQos = env.apply("MAPS_QOS");
      qos = (envQos != null) ? parseQos(envQos) : 1;
    }

    if (requestedOutputFormat == null) {
      String envFormat = env.apply("MAPS_FORMAT");
      if (envFormat != null) {
        requestedOutputFormat = OutputFormat.parse(envFormat);
      }
    }

    // --output-dir takes precedence; only default to rolling mode if --output was not provided
    if (outputDir == null && outputFileName == null) {
      String envOutputDir = env.apply("MAPS_OUTPUT_DIR");
      outputDir = (envOutputDir != null) ? envOutputDir : "/var/log/maps-logger";
    }

    if (diskWarnMb == null) {
      String envWarn = env.apply("MAPS_DISK_WARN_MB");
      diskWarnMb = (envWarn != null) ? parseDiskWarnMb(envWarn) : 500;
    }

    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("Missing required argument: --url (or set MAPS_URL)");
    }

    OutputFormat resolvedFormat = (outputDir != null)
        ? (requestedOutputFormat != null ? requestedOutputFormat : OutputFormat.CSV)
        : OutputFormat.resolve(outputFileName, requestedOutputFormat);

    return new MapsValueLoggerArguments(url, topic, qos, outputFileName, outputDir,
        resolvedFormat, diskWarnMb);
  }

  public static void printUsage() {
    System.err.println("Usage:");
    System.err.println(
        "  maps-value-logger --url <url> [--topic <topic>] [--qos <0|1|2>]");
    System.err.println(
        "    [--format <csv|json>] [--output <file> | --output-dir <dir>]");
    System.err.println(
        "    [--disk-warn-mb <mb>]");
    System.err.println();
    System.err.println(
        "Environment variables: MAPS_URL, MAPS_TOPIC, MAPS_QOS, MAPS_FORMAT,");
    System.err.println(
        "  MAPS_OUTPUT_DIR, MAPS_DISK_WARN_MB");
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

  private static int parseDiskWarnMb(String value) {
    try {
      int mb = Integer.parseInt(value.trim());

      if (mb <= 0) {
        throw new IllegalArgumentException("--disk-warn-mb must be a positive integer");
      }

      return mb;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("--disk-warn-mb must be a positive integer", exception);
    }
  }
}
