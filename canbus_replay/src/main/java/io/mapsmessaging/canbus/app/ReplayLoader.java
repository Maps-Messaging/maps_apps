package io.mapsmessaging.canbus.app;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

public class ReplayLoader {

  private ReplayLoader() {
  }

  public static List<ReplayRecord> load(Path directory) throws IOException {
    List<Path> files = findNdjsonFiles(directory);
    List<ReplayRecord> records = new ArrayList<>();

    for (Path file : files) {
      records.addAll(loadFile(file));
    }

    records.sort(Comparator.comparing(ReplayRecord::timestamp));
    return records;
  }

  private static List<Path> findNdjsonFiles(Path directory) throws IOException {
    try (Stream<Path> stream = Files.walk(directory)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(ReplayLoader::isNdjsonFile)
          .sorted()
          .toList();
    }
  }

  private static boolean isNdjsonFile(Path path) {
    String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return filename.endsWith(".ndjson");
  }

  private static List<ReplayRecord> loadFile(Path file) throws IOException {
    List<ReplayRecord> records = new ArrayList<>();

    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      long lineNumber = 0;

      while ((line = reader.readLine()) != null) {
        lineNumber++;

        if (line.isBlank()) {
          continue;
        }

        records.addAll(parseLine(file, lineNumber, line));
      }
    }

    return records;
  }

  private static List<ReplayRecord> parseLine(Path file, long lineNumber, String line) {
    try {
      JsonObject outerRecord = JsonParser.parseString(line).getAsJsonObject();
      JsonObject replayObject = decodeReplayObject(outerRecord);

      JsonElement envelopesElement = replayObject.get("envelopes");
      if (envelopesElement == null || !envelopesElement.isJsonArray()) {
        return List.of(parseEnvelope(replayObject));
      }

      List<ReplayRecord> records = new ArrayList<>();

      for (JsonElement envelopeElement : envelopesElement.getAsJsonArray()) {
        if (!envelopeElement.isJsonObject()) {
          throw new IllegalArgumentException("Envelope is not a JSON object");
        }
        records.add(parseEnvelope(envelopeElement.getAsJsonObject()));
      }

      return records;
    } catch (RuntimeException e) {
      System.err.println("Invalid replay record in " + file + " line " + lineNumber + ": " + e.getMessage());
      return List.of();
    }
  }

  private static JsonObject decodeReplayObject(JsonObject outerRecord) {
    JsonElement opaqueData = outerRecord.get("opaqueData");

    if (opaqueData == null || opaqueData.isJsonNull() || !opaqueData.isJsonPrimitive() || !opaqueData.getAsJsonPrimitive().isString()) {
      return outerRecord;
    }

    byte[] decoded = Base64.getDecoder().decode(opaqueData.getAsString());
    String decodedJson = new String(decoded, StandardCharsets.UTF_8);

    JsonElement decodedElement = JsonParser.parseString(decodedJson);
    if (!decodedElement.isJsonObject()) {
      System.err.println("Decoded opaqueData is not a JSON object");
    }

    return decodedElement.getAsJsonObject();
  }

  private static ReplayRecord parseEnvelope(JsonObject envelope) {
    JsonObject payload = getRequiredObject(envelope, "payload");

    Instant timestamp = parseTimestamp(envelope);
    int canId = parseRequiredInt(payload, "canId");
    boolean extended = parseRequiredBoolean(payload, "extended");
    int dlc = parseRequiredInt(payload, "dlc");
    byte[] data = parseRequiredBase64(payload, "data");

    return new ReplayRecord(timestamp, canId, extended, dlc, data);
  }

  private static Instant parseTimestamp(JsonObject envelope) {
    JsonObject meta = getOptionalObject(envelope, "meta");

    if (meta != null && meta.has("time_ms")) {
      JsonElement timeMs = meta.get("time_ms");

      if (timeMs.isJsonPrimitive() && timeMs.getAsJsonPrimitive().isNumber()) {
        return Instant.ofEpochMilli(timeMs.getAsLong());
      }

      if (timeMs.isJsonPrimitive() && timeMs.getAsJsonPrimitive().isString()) {
        return Instant.ofEpochMilli(Long.parseLong(timeMs.getAsString()));
      }
    }

    if (envelope.has("timestamp")) {
      JsonElement timestamp = envelope.get("timestamp");

      if (timestamp.isJsonPrimitive() && timestamp.getAsJsonPrimitive().isString()) {
        return Instant.parse(timestamp.getAsString());
      }
    }

    throw new IllegalArgumentException("Missing timestamp. Expected meta.time_ms or timestamp");
  }

  private static String parseTopic(JsonObject envelope) {
    JsonElement topic = envelope.get("topic");

    if (topic == null || topic.isJsonNull() || !topic.isJsonPrimitive() || !topic.getAsJsonPrimitive().isString() || topic.getAsString().isBlank()) {
      throw new IllegalArgumentException("Missing topic");
    }

    return topic.getAsString();
  }

  private static JsonObject getRequiredObject(JsonObject parent, String fieldName) {
    JsonObject object = getOptionalObject(parent, fieldName);

    if (object == null) {
      throw new IllegalArgumentException("Missing object: " + fieldName);
    }

    return object;
  }

  private static JsonObject getOptionalObject(JsonObject parent, String fieldName) {
    JsonElement value = parent.get(fieldName);

    if (value == null || value.isJsonNull()) {
      return null;
    }

    if (!value.isJsonObject()) {
      throw new IllegalArgumentException(fieldName + " is not a JSON object");
    }

    return value.getAsJsonObject();
  }

  private static int parseRequiredInt(JsonObject parent, String fieldName) {
    JsonElement value = parent.get(fieldName);

    if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
      throw new IllegalArgumentException("Missing numeric payload." + fieldName);
    }

    if (value.getAsJsonPrimitive().isNumber()) {
      return Math.toIntExact(Math.round(value.getAsDouble()));
    }

    if (value.getAsJsonPrimitive().isString()) {
      return Integer.parseInt(value.getAsString());
    }

    throw new IllegalArgumentException("Missing numeric payload." + fieldName);
  }

  private static boolean parseRequiredBoolean(JsonObject parent, String fieldName) {
    JsonElement value = parent.get(fieldName);

    if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
      throw new IllegalArgumentException("Missing boolean payload." + fieldName);
    }

    if (value.getAsJsonPrimitive().isBoolean()) {
      return value.getAsBoolean();
    }

    if (value.getAsJsonPrimitive().isString()) {
      return Boolean.parseBoolean(value.getAsString());
    }

    throw new IllegalArgumentException("Missing boolean payload." + fieldName);
  }

  private static byte[] parseRequiredBase64(JsonObject parent, String fieldName) {
    JsonElement value = parent.get(fieldName);

    if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
      throw new IllegalArgumentException("Missing base64 payload." + fieldName);
    }

    return Base64.getDecoder().decode(value.getAsString());
  }
}