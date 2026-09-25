/*
 *
 *  Copyright [ 2020 - 2024 ] Matthew Buckton
 *  Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *
 */

package io.mapsmessaging.audit.viewer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public class AuditEvidenceResolver {

  private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private final Path auditRoot;
  private final Path payloadRoot;
  private final Path taskEvidenceRoot;

  public AuditEvidenceResolver(Path auditPath) {
    this.auditRoot = determineAuditRoot(auditPath);
    this.payloadRoot = auditRoot.resolve("payloads").normalize();
    this.taskEvidenceRoot = auditRoot.resolve("task-evidence").normalize();
  }

  public Path getAuditRoot() {
    return auditRoot;
  }

  public List<AuditPayloadEvidence> resolvePayloads(AuditRecordView record) {
    List<AuditPayloadEvidence> result = new ArrayList<>();
    JsonObject rawRecord = record.getRawRecord();
    if (rawRecord == null || !rawRecord.has("payloadReferences")
        || !rawRecord.get("payloadReferences").isJsonArray()) {
      return result;
    }

    for (JsonElement element : rawRecord.getAsJsonArray("payloadReferences")) {
      if (element.isJsonObject()) {
        result.add(resolvePayload(element.getAsJsonObject()));
      }
    }
    return result;
  }

  public boolean matchesTask(AuditRecordView record, String taskId) {
    if (taskId == null || taskId.isBlank()) {
      return true;
    }
    JsonObject rawRecord = record.getRawRecord();
    if (rawRecord == null) {
      return false;
    }

    if (matches(rawRecord, "correlationId", taskId) || matches(rawRecord, "auditId", taskId)) {
      return true;
    }

    if (rawRecord.has("attributes") && rawRecord.get("attributes").isJsonObject()) {
      JsonObject attributes = rawRecord.getAsJsonObject("attributes");
      return matches(attributes, "taskId", taskId)
          || matches(attributes, "storageId", taskId)
          || matches(attributes, "missionId", taskId);
    }

    return false;
  }

  public String taskId(AuditRecordView record) {
    JsonObject rawRecord = record.getRawRecord();
    if (rawRecord == null || !rawRecord.has("attributes")
        || !rawRecord.get("attributes").isJsonObject()) {
      return "";
    }

    JsonObject attributes = rawRecord.getAsJsonObject("attributes");
    String taskId = string(attributes, "taskId");
    if (!taskId.isBlank()) {
      return taskId;
    }
    return string(attributes, "storageId");
  }

  public List<JsonObject> readIndexedTaskEvidence(String taskId) throws IOException {
    if (taskId == null || taskId.isBlank()) {
      return List.of();
    }

    Path directory = taskEvidenceRoot.resolve(safeIdentifier(taskId)).normalize();
    requireWithin(taskEvidenceRoot, directory);
    if (!Files.isDirectory(directory)) {
      return List.of();
    }

    try (var files = Files.list(directory)) {
      List<JsonObject> result = new ArrayList<>();
      for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
        JsonObject indexed = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (indexed.has("record") && indexed.get("record").isJsonObject()) {
          result.add(indexed.getAsJsonObject("record"));
        }
      }
      return result;
    }
  }

  private AuditPayloadEvidence resolvePayload(JsonObject reference) {
    String relativePath = string(reference, "path");
    if (relativePath.isBlank()) {
      return new AuditPayloadEvidence(reference.deepCopy(), null, false, false, false,
          "", 0, "", "Missing payload path");
    }

    Path payloadPath = payloadRoot.resolve(relativePath).normalize();
    if (!payloadPath.startsWith(payloadRoot)) {
      return new AuditPayloadEvidence(reference.deepCopy(), payloadPath, false, false, false,
          "", 0, "", "Payload path escapes audit payload root");
    }

    if (!Files.isRegularFile(payloadPath)) {
      return new AuditPayloadEvidence(reference.deepCopy(), payloadPath, false, true, false,
          "", 0, "", "Payload file is missing");
    }

    try {
      byte[] bytes = Files.readAllBytes(payloadPath);
      String calculated = sha256(bytes);
      String expected = string(reference, "sha256");
      boolean shaValid = !expected.isBlank() && expected.equalsIgnoreCase(calculated);
      String rendered = renderPayload(reference, bytes);
      return new AuditPayloadEvidence(reference.deepCopy(), payloadPath, true, true, shaValid,
          calculated, bytes.length, rendered, shaValid ? "SHA-256 valid" : "SHA-256 mismatch");
    } catch (IOException exception) {
      return new AuditPayloadEvidence(reference.deepCopy(), payloadPath, false, true, false,
          "", 0, "", "Unable to read payload: " + exception.getMessage());
    }
  }

  private String renderPayload(JsonObject reference, byte[] bytes) {
    String contentType = string(reference, "contentType").toLowerCase(Locale.ROOT);
    String path = string(reference, "path").toLowerCase(Locale.ROOT);
    boolean textual = contentType.startsWith("text/")
        || contentType.contains("json")
        || contentType.contains("xml")
        || path.endsWith(".json")
        || path.endsWith(".jsonl")
        || path.endsWith(".txt")
        || path.endsWith(".xml");

    if (!textual) {
      return Base64.getEncoder().encodeToString(bytes);
    }

    String text = new String(bytes, StandardCharsets.UTF_8);
    if (contentType.contains("json") || path.endsWith(".json")) {
      try {
        return prettyGson.toJson(JsonParser.parseString(text));
      } catch (Exception ignored) {
        return text;
      }
    }
    return text;
  }

  private Path determineAuditRoot(Path input) {
    Path absolute = input.toAbsolutePath().normalize();
    Path current = Files.isRegularFile(absolute) ? absolute.getParent() : absolute;

    while (current != null) {
      Path name = current.getFileName();
      if (name != null && "journal".equals(name.toString())) {
        Path parent = current.getParent();
        return parent == null ? current : parent;
      }
      current = current.getParent();
    }

    return absolute;
  }

  private void requireWithin(Path root, Path path) throws IOException {
    if (!path.startsWith(root)) {
      throw new IOException("Audit evidence path escapes configured root");
    }
  }

  private boolean matches(JsonObject object, String name, String expected) {
    return expected.equals(string(object, name));
  }

  private String string(JsonObject object, String name) {
    if (!object.has(name) || object.get(name).isJsonNull()) {
      return "";
    }
    return object.get(name).getAsString();
  }

  private String safeIdentifier(String value) {
    return value.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private String sha256(byte[] payload) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException exception) {
      throw new IOException("SHA-256 is unavailable", exception);
    }
  }
}
