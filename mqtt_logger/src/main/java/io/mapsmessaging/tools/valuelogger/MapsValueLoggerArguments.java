package io.mapsmessaging.tools.valuelogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import lombok.Getter;

@Getter
public class MapsValueLoggerArguments {

  private final String url;
  private final List<String> topics;
  private final int qos;
  private final String outputFileName;
  private final String outputDir;
  private final OutputFormat outputFormat;
  private final int diskWarnMb;
  private final String username;
  private final String passwordFile;
  private final String keyStore;
  private final String keyStorePasswordFile;
  private final String trustStore;
  private final String trustStorePasswordFile;

  private MapsValueLoggerArguments(
      String url,
      List<String> topics,
      int qos,
      String outputFileName,
      String outputDir,
      OutputFormat outputFormat,
      int diskWarnMb,
      String username,
      String passwordFile,
      String keyStore,
      String keyStorePasswordFile,
      String trustStore,
      String trustStorePasswordFile) {
    this.url = url;
    this.topics = topics;
    this.qos = qos;
    this.outputFileName = outputFileName;
    this.outputDir = outputDir;
    this.outputFormat = outputFormat;
    this.diskWarnMb = diskWarnMb;
    this.username = username;
    this.passwordFile = passwordFile;
    this.keyStore = keyStore;
    this.keyStorePasswordFile = keyStorePasswordFile;
    this.trustStore = trustStore;
    this.trustStorePasswordFile = trustStorePasswordFile;
  }

  public boolean isTlsUrl() {
    return url.regionMatches(true, 0, "ssl://", 0, "ssl://".length());
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
    String username = null;
    String passwordFile = null;
    String keyStore = null;
    String keyStorePasswordFile = null;
    String trustStore = null;
    String trustStorePasswordFile = null;

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

        case "--username":
          username = readRequiredValue(args, ++index, "--username");
          break;

        case "--password-file":
          passwordFile = readRequiredValue(args, ++index, "--password-file");
          break;

        case "--keystore":
          keyStore = readRequiredValue(args, ++index, "--keystore");
          break;

        case "--keystore-password-file":
          keyStorePasswordFile = readRequiredValue(args, ++index, "--keystore-password-file");
          break;

        case "--truststore":
          trustStore = readRequiredValue(args, ++index, "--truststore");
          break;

        case "--truststore-password-file":
          trustStorePasswordFile = readRequiredValue(args, ++index, "--truststore-password-file");
          break;

        default:
          throw new IllegalArgumentException("Unknown argument: " + argument);
      }
    }

    if (outputDir != null && outputFileName != null) {
      throw new IllegalArgumentException("--output and --output-dir are mutually exclusive");
    }

    // Env-var fallback, then built-in defaults
    if (url == null) {
      url = env.apply("MAPS_LOGGER_URL");
    }

    if (topic == null) {
      String envTopic = env.apply("MAPS_LOGGER_TOPIC");
      topic = (envTopic != null) ? envTopic : "#";
    }

    if (qos == null) {
      String envQos = env.apply("MAPS_LOGGER_QOS");
      qos = (envQos != null) ? parseQos(envQos) : 1;
    }

    if (requestedOutputFormat == null) {
      String envFormat = env.apply("MAPS_LOGGER_FORMAT");
      if (envFormat != null) {
        requestedOutputFormat = OutputFormat.parse(envFormat);
      }
    }

    // --output-dir takes precedence; only default to rolling mode if --output was not provided
    if (outputDir == null && outputFileName == null) {
      String envOutputDir = env.apply("MAPS_LOGGER_OUTPUT_DIR");
      outputDir = (envOutputDir != null) ? envOutputDir : "/var/log/maps-logger";
    }

    if (diskWarnMb == null) {
      String envWarn = env.apply("MAPS_LOGGER_DISK_WARN_MB");
      diskWarnMb = (envWarn != null) ? parseDiskWarnMb(envWarn) : 500;
    }

    username = fallback(username, env, "MAPS_LOGGER_USERNAME");
    passwordFile = fallback(passwordFile, env, "MAPS_LOGGER_PASSWORD_FILE");
    keyStore = fallback(keyStore, env, "MAPS_LOGGER_KEYSTORE");
    keyStorePasswordFile = fallback(keyStorePasswordFile, env, "MAPS_LOGGER_KEYSTORE_PASSWORD_FILE");
    trustStore = fallback(trustStore, env, "MAPS_LOGGER_TRUSTSTORE");
    trustStorePasswordFile = fallback(trustStorePasswordFile, env, "MAPS_LOGGER_TRUSTSTORE_PASSWORD_FILE");

    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("Missing required argument: --url (or set MAPS_LOGGER_URL)");
    }

    OutputFormat resolvedFormat = (outputDir != null)
        ? (requestedOutputFormat != null ? requestedOutputFormat : OutputFormat.CSV)
        : OutputFormat.resolve(outputFileName, requestedOutputFormat);

    MapsValueLoggerArguments arguments = new MapsValueLoggerArguments(url, parseTopics(topic), qos,
        outputFileName, outputDir, resolvedFormat, diskWarnMb, username, passwordFile, keyStore,
        keyStorePasswordFile, trustStore, trustStorePasswordFile);
    arguments.validateSecurity();
    return arguments;
  }

  private void validateSecurity() {
    if (passwordFile != null && username == null) {
      throw new IllegalArgumentException("--password-file requires --username");
    }

    if ((keyStore != null || trustStore != null) && !isTlsUrl()) {
      throw new IllegalArgumentException("--keystore and --truststore require an ssl:// URL");
    }

    if (keyStore != null && keyStorePasswordFile == null) {
      throw new IllegalArgumentException("--keystore requires --keystore-password-file");
    }

    if (trustStore != null && trustStorePasswordFile == null) {
      throw new IllegalArgumentException("--truststore requires --truststore-password-file");
    }

    requireReadable(passwordFile, "--password-file");
    requireReadable(keyStore, "--keystore");
    requireReadable(keyStorePasswordFile, "--keystore-password-file");
    requireReadable(trustStore, "--truststore");
    requireReadable(trustStorePasswordFile, "--truststore-password-file");
  }

  private static void requireReadable(String file, String name) {
    if (file != null && !Files.isReadable(Path.of(file))) {
      throw new IllegalArgumentException(name + " is not a readable file: " + file);
    }
  }

  private static String fallback(String value, Function<String, String> env, String name) {
    if (value != null) {
      return value;
    }

    String envValue = env.apply(name);
    return (envValue == null || envValue.isBlank()) ? null : envValue;
  }

  private static List<String> parseTopics(String topicText) {
    List<String> parsed = Arrays.stream(topicText.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();

    if (parsed.isEmpty()) {
      throw new IllegalArgumentException("--topic must contain at least one topic filter");
    }

    return parsed;
  }

  public static void printUsage() {
    System.err.println("Usage:");
    System.err.println(
        "  maps-value-logger --url <url> [--topic <topic>[,<topic>...]] [--qos <0|1|2>]");
    System.err.println(
        "    [--format <csv|json>] [--output <file> | --output-dir <dir>]");
    System.err.println(
        "    [--disk-warn-mb <mb>] [--username <name> [--password-file <file>]]");
    System.err.println(
        "    [--keystore <p12> --keystore-password-file <file>]");
    System.err.println(
        "    [--truststore <p12> --truststore-password-file <file>]");
    System.err.println();
    System.err.println(
        "Environment variables: MAPS_LOGGER_URL, MAPS_LOGGER_TOPIC, MAPS_LOGGER_QOS, MAPS_LOGGER_FORMAT,");
    System.err.println(
        "  MAPS_LOGGER_OUTPUT_DIR, MAPS_LOGGER_DISK_WARN_MB, MAPS_LOGGER_USERNAME,");
    System.err.println(
        "  MAPS_LOGGER_PASSWORD_FILE, MAPS_LOGGER_KEYSTORE, MAPS_LOGGER_KEYSTORE_PASSWORD_FILE,");
    System.err.println(
        "  MAPS_LOGGER_TRUSTSTORE, MAPS_LOGGER_TRUSTSTORE_PASSWORD_FILE");
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
