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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AuditEvidenceSummaryConsolePrinter {

  private final AuditEvidenceResolver evidenceResolver;

  public AuditEvidenceSummaryConsolePrinter(AuditEvidenceResolver evidenceResolver) {
    this.evidenceResolver = evidenceResolver;
  }

  public void print(List<AuditRecordView> records) {
    Map<String, List<AuditRecordView>> byTask = records.stream()
        .filter(record -> !evidenceResolver.taskId(record).isBlank())
        .collect(java.util.stream.Collectors.groupingBy(
            evidenceResolver::taskId,
            LinkedHashMap::new,
            java.util.stream.Collectors.toList()));

    if (byTask.isEmpty()) {
      printGroup("(unidentified)", records);
      return;
    }

    for (Map.Entry<String, List<AuditRecordView>> entry : byTask.entrySet()) {
      printGroup(entry.getKey(), entry.getValue());
    }
  }

  private void printGroup(String taskId, List<AuditRecordView> records) {
    long validRecords = records.stream()
        .filter(record -> record.getStatus() == AuditRecordVerificationStatus.VALID)
        .count();

    List<AuditPayloadEvidence> payloads = records.stream()
        .flatMap(record -> evidenceResolver.resolvePayloads(record).stream())
        .toList();

    long validPayloads = payloads.stream().filter(AuditPayloadEvidence::sha256Valid).count();

    System.out.println("================================================================================");
    System.out.println("Task:                 " + taskId);
    System.out.println("Audit records:        " + records.size());
    System.out.println("Journal valid:        " + validRecords + "/" + records.size());
    System.out.println("Payloads:             " + payloads.size());
    System.out.println("Payload SHA-256 valid:" + validPayloads + "/" + payloads.size());

    if (!"(unidentified)".equals(taskId)) {
      try {
        System.out.println("Indexed evidence:     " + evidenceResolver.readIndexedTaskEvidence(taskId).size());
      } catch (IOException exception) {
        System.out.println("Indexed evidence:     ERROR " + exception.getMessage());
      }
    }

    System.out.println();
    System.out.println("Timeline:");
    for (AuditRecordView record : records) {
      System.out.printf(
          "  %-30s %-26s %-10s integrity=%s%n",
          record.getTimestamp(),
          record.getAction(),
          record.getOutcome(),
          record.getStatus()
      );
    }
  }
}
