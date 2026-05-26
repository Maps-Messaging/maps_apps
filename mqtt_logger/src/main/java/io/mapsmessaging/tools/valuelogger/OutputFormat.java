package io.mapsmessaging.tools.valuelogger;

public enum OutputFormat {
  CSV,
  JSON;

  public static OutputFormat parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    String normalisedValue = value.trim().toUpperCase();

    if ("CSV".equals(normalisedValue)) {
      return CSV;
    }

    if ("JSON".equals(normalisedValue) || "NDJSON".equals(normalisedValue)) {
      return JSON;
    }

    throw new IllegalArgumentException("--format must be csv or json");
  }

  public static OutputFormat resolve(String outputFileName, OutputFormat requestedFormat) {
    if (requestedFormat != null) {
      return requestedFormat;
    }

    if (outputFileName == null || outputFileName.isBlank() || "-".equals(outputFileName)) {
      return CSV;
    }

    String lowerCaseOutputFileName = outputFileName.toLowerCase();

    if (lowerCaseOutputFileName.endsWith(".json") || lowerCaseOutputFileName.endsWith(".ndjson")) {
      return JSON;
    }

    return CSV;
  }
}