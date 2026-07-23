package io.mapsmessaging.tools.mavlink.replay;

import java.util.Locale;

public enum ReplayFormat {
  AUTO,
  TLOG,
  MUDP,
  LEGACY_DAT,
  RAW;

  public static ReplayFormat parse(String value) {
    if (value == null || value.isBlank()) {
      return AUTO;
    }

    String normalised = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    if ("DAT".equals(normalised) || "CSV".equals(normalised)) {
      return LEGACY_DAT;
    }
    if ("RLOG".equals(normalised) || "BIN".equals(normalised)) {
      return RAW;
    }
    return ReplayFormat.valueOf(normalised);
  }
}
