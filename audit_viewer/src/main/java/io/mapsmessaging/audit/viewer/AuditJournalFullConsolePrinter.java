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
import java.util.List;

public class AuditJournalFullConsolePrinter {

  private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private final AuditEvidenceResolver evidenceResolver;

  public AuditJournalFullConsolePrinter(AuditEvidenceResolver evidenceResolver) {
    this.evidenceResolver = evidenceResolver;
  }

  public void print(List<AuditRecordView> records) {
    for (AuditRecordView record : records) {
      printRecord(record);
    }
    System.out.println("Records: " + records.size());
  }

  private void printRecord(AuditRecordView record) {
    System.out.println("================================================================================");
    System.out.println("Journal:    " + record.getJournalPath());
    System.out.println("Line:       " + record.getLineNumber());
    System.out.println("Sequence:   " + record.getSequenceNumber());
    System.out.println("Integrity:  " + record.getStatus() + " (" + record.getValidationMessage() + ")");
    System.out.println("Outcome:    " + record.getOutcome());
    System.out.println("Action:     " + record.getAction());
    System.out.println();

    System.out.println("Record JSON:");
    if (record.getRawRecord() == null) {
      System.out.println("<unavailable>");
    } else {
      System.out.println(gson.toJson(record.getRawRecord()));
    }

    List<AuditPayloadEvidence> payloads = evidenceResolver.resolvePayloads(record);
    if (payloads.isEmpty()) {
      System.out.println();
      System.out.println("Evidence: none");
      return;
    }

    System.out.println();
    System.out.println("Evidence:");
    int index = 1;
    for (AuditPayloadEvidence payload : payloads) {
      System.out.println("  [" + index++ + "]");
      System.out.println("    Reference: " + gson.toJson(payload.reference()));
      System.out.println("    Path:      " + (payload.path() == null ? "" : payload.path()));
      System.out.println("    Present:   " + payload.present());
      System.out.println("    Path valid:" + payload.pathValid());
      System.out.println("    SHA-256:   " + payload.sha256Valid() + " (" + payload.validationMessage() + ")");
      System.out.println("    Calculated:" + payload.calculatedSha256());
      System.out.println("    Size:      " + payload.size() + " bytes");
      System.out.println("    Data:");
      for (String line : payload.renderedData().split("\\R", -1)) {
        System.out.println("      " + line);
      }
    }
  }
}
