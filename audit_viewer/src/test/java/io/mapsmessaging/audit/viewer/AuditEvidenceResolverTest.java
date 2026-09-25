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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditEvidenceResolverTest {

  @TempDir
  private Path temporaryDirectory;

  @Test
  void shouldResolveVerifyAndRenderJsonPayload() throws Exception {
    Path auditRoot = temporaryDirectory.resolve("audit");
    Path payload = auditRoot.resolve("payloads/task-1/request.json");
    Files.createDirectories(payload.getParent());
    byte[] content = "{\"task\":\"PATROL\"}".getBytes(StandardCharsets.UTF_8);
    Files.write(payload, content);

    AuditRecordView record = recordWithPayload(
        "task-1/request.json",
        sha256(content),
        "application/json",
        "task-1"
    );

    AuditEvidenceResolver resolver = new AuditEvidenceResolver(auditRoot);
    AuditPayloadEvidence evidence = resolver.resolvePayloads(record).get(0);

    assertTrue(evidence.present());
    assertTrue(evidence.pathValid());
    assertTrue(evidence.sha256Valid());
    assertTrue(evidence.renderedData().contains("\"task\": \"PATROL\""));
    assertTrue(resolver.matchesTask(record, "task-1"));
  }

  @Test
  void shouldReportShaMismatchWithoutRejectingPath() throws Exception {
    Path auditRoot = temporaryDirectory.resolve("audit");
    Path payload = auditRoot.resolve("payloads/task-1/request.json");
    Files.createDirectories(payload.getParent());
    Files.writeString(payload, "changed");

    AuditRecordView record = recordWithPayload(
        "task-1/request.json",
        "0000",
        "application/json",
        "task-1"
    );

    AuditPayloadEvidence evidence = new AuditEvidenceResolver(auditRoot).resolvePayloads(record).get(0);

    assertTrue(evidence.present());
    assertTrue(evidence.pathValid());
    assertFalse(evidence.sha256Valid());
  }

  @Test
  void shouldRejectPayloadPathTraversal() {
    Path auditRoot = temporaryDirectory.resolve("audit");
    AuditRecordView record = recordWithPayload(
        "../outside.bin",
        "0000",
        "application/octet-stream",
        "task-1"
    );

    AuditPayloadEvidence evidence = new AuditEvidenceResolver(auditRoot).resolvePayloads(record).get(0);

    assertFalse(evidence.pathValid());
    assertFalse(evidence.present());
  }

  @Test
  void shouldReportMissingPayloadWithoutFailingRecord() {
    Path auditRoot = temporaryDirectory.resolve("audit");
    AuditRecordView record = recordWithPayload(
        "task-1/missing.bin",
        "0000",
        "application/octet-stream",
        "task-1"
    );

    AuditPayloadEvidence evidence = new AuditEvidenceResolver(auditRoot).resolvePayloads(record).get(0);

    assertTrue(evidence.pathValid());
    assertFalse(evidence.present());
    assertTrue(evidence.validationMessage().contains("missing"));
  }

  private AuditRecordView recordWithPayload(
      String path,
      String sha256,
      String contentType,
      String taskId
  ) {
    JsonObject reference = new JsonObject();
    reference.addProperty("path", path);
    reference.addProperty("sha256", sha256);
    reference.addProperty("contentType", contentType);

    JsonArray references = new JsonArray();
    references.add(reference);

    JsonObject attributes = new JsonObject();
    attributes.addProperty("taskId", taskId);
    attributes.addProperty("storageId", taskId);

    JsonObject rawRecord = new JsonObject();
    rawRecord.add("attributes", attributes);
    rawRecord.add("payloadReferences", references);

    return AuditRecordView.builder()
        .rawRecord(rawRecord)
        .status(AuditRecordVerificationStatus.VALID)
        .build();
  }

  private String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }
}
