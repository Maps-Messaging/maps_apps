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

import io.mapsmessaging.audit.AuditKeyUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.EdECPublicKey;
import java.util.ArrayList;
import java.util.List;

public class AuditJournalViewCommand {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      printUsage();
      return;
    }

    Path auditPath = Path.of(args[0]);
    EdECPublicKey publicKey = null;
    boolean full = false;
    boolean evidenceSummary = false;
    String taskId = null;

    for (int index = 1; index < args.length; index++) {
      switch (args[index]) {
        case "--public-key" -> {
          if (++index >= args.length) {
            printUsage();
            return;
          }
          AuditKeyUtils auditKeyUtils = new AuditKeyUtils();
          publicKey = auditKeyUtils.readPublicKey(Path.of(args[index]));
        }
        case "--full", "--verbose" -> full = true;
        case "--evidence-summary" -> evidenceSummary = true;
        case "--task" -> {
          if (++index >= args.length) {
            printUsage();
            return;
          }
          taskId = args[index];
        }
        default -> {
          printUsage();
          return;
        }
      }
    }

    AuditJournalViewer auditJournalViewer = new AuditJournalViewer(publicKey);
    List<AuditRecordView> records;

    if (Files.isDirectory(auditPath)) {
      records = auditJournalViewer.readAndVerifyJournalRoot(auditPath);
    } else {
      records = auditJournalViewer.readAndVerify(auditPath);
    }

    AuditEvidenceResolver evidenceResolver = new AuditEvidenceResolver(auditPath);
    if (taskId != null) {
      List<AuditRecordView> filtered = new ArrayList<>();
      for (AuditRecordView record : records) {
        if (evidenceResolver.matchesTask(record, taskId)) {
          filtered.add(record);
        }
      }
      records = filtered;
    }

    if (evidenceSummary) {
      new AuditEvidenceSummaryConsolePrinter(evidenceResolver).print(records);
    } else if (full) {
      new AuditJournalFullConsolePrinter(evidenceResolver).print(records);
    } else {
      new AuditJournalConsolePrinter().print(records);
    }
  }

  private static void printUsage() {
    System.out.println("Usage:");
    System.out.println("  maps-audit-view <audit-root|journal-root|journal.jsonl> [options]");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  --public-key <audit-public-key.pem>  Verify Ed25519 signatures");
    System.out.println("  --full | --verbose                   Print complete records and payload evidence");
    System.out.println("  --task <task-id>                     Filter records to one task/correlation");
    System.out.println("  --evidence-summary                   Print task-level evidence summary");
  }
}
