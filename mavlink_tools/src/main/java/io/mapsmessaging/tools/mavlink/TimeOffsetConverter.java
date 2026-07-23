package io.mapsmessaging.tools.mavlink;

import picocli.CommandLine.ITypeConverter;

import java.time.Duration;

public class TimeOffsetConverter implements ITypeConverter<Duration> {

  @Override
  public Duration convert(String value) {
    return parse(value);
  }

  public static Duration parse(String value) {
    if (value == null || value.isBlank()) {
      return Duration.ZERO;
    }

    String trimmed = value.trim().toLowerCase();
    if (trimmed.endsWith("ms")) {
      return Duration.ofMillis(parseLong(trimmed.substring(0, trimmed.length() - 2), value));
    }
    if (trimmed.endsWith("s")) {
      return Duration.ofSeconds(parseLong(trimmed.substring(0, trimmed.length() - 1), value));
    }
    if (trimmed.endsWith("m")) {
      return Duration.ofMinutes(parseLong(trimmed.substring(0, trimmed.length() - 1), value));
    }
    if (trimmed.endsWith("h")) {
      return Duration.ofHours(parseLong(trimmed.substring(0, trimmed.length() - 1), value));
    }

    String[] components = trimmed.split(":");
    if (components.length == 1) {
      return Duration.ofSeconds(parseLong(components[0], value));
    }
    if (components.length == 2) {
      long minutes = parseLong(components[0], value);
      long seconds = parseLong(components[1], value);
      validateClockComponent(seconds, value);
      return Duration.ofMinutes(minutes).plusSeconds(seconds);
    }
    if (components.length == 3) {
      long hours = parseLong(components[0], value);
      long minutes = parseLong(components[1], value);
      long seconds = parseLong(components[2], value);
      validateClockComponent(minutes, value);
      validateClockComponent(seconds, value);
      return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds);
    }
    throw new IllegalArgumentException("Invalid offset: " + value);
  }

  private static long parseLong(String value, String originalValue) {
    try {
      long parsed = Long.parseLong(value.trim());
      if (parsed < 0) {
        throw new IllegalArgumentException("Offset must not be negative: " + originalValue);
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Invalid offset: " + originalValue, exception);
    }
  }

  private static void validateClockComponent(long value, String originalValue) {
    if (value > 59) {
      throw new IllegalArgumentException("Invalid offset: " + originalValue);
    }
  }
}
