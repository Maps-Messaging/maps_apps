package io.mapsmessaging.apps.video;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public record VideoRecorderConfig(
    String mqttUrl,
    String mqttUsername,
    String mqttPassword,
    String requestTopic,
    String resultTopic,
    int qos,
    String mediaMtxPlaybackUrl,
    String mediaMtxUsername,
    String mediaMtxPassword,
    String s3Bucket,
    String s3Region,
    String s3Prefix,
    int defaultBeforeSeconds,
    int defaultAfterSeconds,
    int dedupeRetentionSeconds,
    int urlValiditySeconds,
    int settleSeconds) {

  private static final Set<String> ALLOWED_ARGUMENTS =
      Set.of(
          "--mqtt-url",
          "--mqtt-username",
          "--mqtt-password",
          "--request-topic",
          "--result-topic",
          "--qos",
          "--mediamtx-url",
          "--mediamtx-username",
          "--mediamtx-password",
          "--s3-bucket",
          "--s3-region",
          "--s3-prefix",
          "--before-seconds",
          "--after-seconds",
          "--dedupe-retention-seconds",
          "--url-validity-seconds",
          "--settle-seconds");

  public static VideoRecorderConfig parse(String[] args) {
    return parse(args, System::getenv);
  }

  static VideoRecorderConfig parse(String[] args, Function<String, String> env) {
    Map<String, String> values = new HashMap<>();
    for (int index = 0; index < args.length; index++) {
      String name = args[index];
      if (!ALLOWED_ARGUMENTS.contains(name)) {
        throw new IllegalArgumentException("Unknown argument: " + name);
      }
      if (index + 1 >= args.length) {
        throw new IllegalArgumentException("Missing value for argument: " + name);
      }
      String value = args[++index];
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Blank value for argument: " + name);
      }
      values.put(name, value);
    }

    String mqttUrl = value(values, "--mqtt-url", env, "MAPS_VIDEO_MQTT_URL", null);
    String mqttUsername = value(values, "--mqtt-username", env, "MAPS_VIDEO_MQTT_USERNAME", null);
    String mqttPassword = value(values, "--mqtt-password", env, "MAPS_VIDEO_MQTT_PASSWORD", null);
    String requestTopic =
        value(values, "--request-topic", env, "MAPS_VIDEO_REQUEST_TOPIC", "maps/video/record/request");
    String resultTopic =
        value(values, "--result-topic", env, "MAPS_VIDEO_RESULT_TOPIC", "maps/video/record/result");
    int qos = integer(values, "--qos", env, "MAPS_VIDEO_QOS", 1, 0, 2);
    String playbackUrl = value(values, "--mediamtx-url", env, "MAPS_VIDEO_MEDIAMTX_URL", null);
    String mediaMtxUsername =
        value(values, "--mediamtx-username", env, "MAPS_VIDEO_MEDIAMTX_USERNAME", null);
    String mediaMtxPassword =
        value(values, "--mediamtx-password", env, "MAPS_VIDEO_MEDIAMTX_PASSWORD", null);
    String bucket = value(values, "--s3-bucket", env, "MAPS_VIDEO_S3_BUCKET", null);
    String region = value(values, "--s3-region", env, "MAPS_VIDEO_S3_REGION", null);
    String prefix = value(values, "--s3-prefix", env, "MAPS_VIDEO_S3_PREFIX", "detections");
    int before =
        integer(values, "--before-seconds", env, "MAPS_VIDEO_BEFORE_SECONDS", 30, 0, 86400);
    int after =
        integer(values, "--after-seconds", env, "MAPS_VIDEO_AFTER_SECONDS", 60, 0, 86400);
    int dedupe =
        integer(
            values,
            "--dedupe-retention-seconds",
            env,
            "MAPS_VIDEO_DEDUPE_RETENTION_SECONDS",
            1800,
            1,
            604800);
    int urlValidity =
        integer(
            values,
            "--url-validity-seconds",
            env,
            "MAPS_VIDEO_URL_VALIDITY_SECONDS",
            86400,
            1,
            604800);
    int settle =
        integer(values, "--settle-seconds", env, "MAPS_VIDEO_SETTLE_SECONDS", 2, 0, 60);

    require(mqttUrl, "--mqtt-url or MAPS_VIDEO_MQTT_URL");
    require(playbackUrl, "--mediamtx-url or MAPS_VIDEO_MEDIAMTX_URL");
    require(bucket, "--s3-bucket or MAPS_VIDEO_S3_BUCKET");
    require(region, "--s3-region or MAPS_VIDEO_S3_REGION");
    if (before + after <= 0) {
      throw new IllegalArgumentException("before-seconds and after-seconds cannot both be zero");
    }

    return new VideoRecorderConfig(
        mqttUrl,
        blankToNull(mqttUsername),
        blankToNull(mqttPassword),
        requestTopic,
        resultTopic,
        qos,
        stripTrailingSlash(playbackUrl),
        blankToNull(mediaMtxUsername),
        blankToNull(mediaMtxPassword),
        bucket,
        region,
        trimSlashes(prefix),
        before,
        after,
        dedupe,
        urlValidity,
        settle);
  }

  public static void printUsage() {
    System.err.println("Usage: maps-video-event-recorder [options]");
    System.err.println();
    System.err.println("Required:");
    System.err.println("  --mqtt-url <url>          MQTT broker URL");
    System.err.println("  --mediamtx-url <url>      MediaMTX playback URL, for example http://127.0.0.1:9996");
    System.err.println("  --s3-bucket <bucket>      Destination S3 bucket");
    System.err.println("  --s3-region <region>      AWS region");
    System.err.println();
    System.err.println("Optional:");
    System.err.println("  --request-topic <topic>   Default maps/video/record/request");
    System.err.println("  --result-topic <topic>    Default maps/video/record/result");
    System.err.println("  --before-seconds <n>      Default 30");
    System.err.println("  --after-seconds <n>       Default 60");
    System.err.println("  --settle-seconds <n>      Default 2");
    System.err.println("  --dedupe-retention-seconds <n>  Default 1800");
    System.err.println("  --url-validity-seconds <n>      Default 86400, maximum 604800");
    System.err.println();
    System.err.println("Equivalent MAPS_VIDEO_* environment variables are also supported.");
  }

  private static String value(
      Map<String, String> values,
      String argument,
      Function<String, String> env,
      String envName,
      String defaultValue) {
    String value = values.get(argument);
    if (value != null) {
      return value;
    }
    String environmentValue = env.apply(envName);
    return environmentValue == null ? defaultValue : environmentValue;
  }

  private static int integer(
      Map<String, String> values,
      String argument,
      Function<String, String> env,
      String envName,
      int defaultValue,
      int minimum,
      int maximum) {
    String value = value(values, argument, env, envName, Integer.toString(defaultValue));
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < minimum || parsed > maximum) {
        throw new IllegalArgumentException(
            argument + " must be between " + minimum + " and " + maximum);
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(argument + " must be an integer", exception);
    }
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required configuration: " + name);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String trimSlashes(String value) {
    if (value == null) {
      return "";
    }
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == '/') {
      start++;
    }
    while (end > start && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(start, end);
  }
}
