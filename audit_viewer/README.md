# Audit Viewer

The Audit Viewer provides a simple console view over the append-only audit journal.

The audit journal is stored as JSON Lines (`.jsonl`), where each line is a complete audit record. The viewer reads the journal, verifies the hash chain and optional signature, and prints each record with a `VALID` or `INVALID` status.

This is intended for operational review, debugging, and incident investigation. The audit journal remains the source of truth.

## Purpose

The audit system records events that matter, such as:

- configuration changes
- authentication or authorisation events
- security-sensitive actions
- administrative actions
- externally meaningful state transitions
- important business or system decisions
- command or request lifecycle events
- safety or policy decisions

It is not intended for high-frequency tracing, packet-level logging, or general debug output.

Normal logging should continue to use the standard logging path, such as Logback. Audit logging is for events that need an accountable, tamper-evident trail.

## Audit Journal Format

The audit journal is an append-only JSONL file.

Example:

```jsonl
{"sequenceNumber":1,"auditId":"audit-1","correlationId":"request-123","timestamp":"2026-05-25T00:00:00Z","action":"request-received","outcome":"SUCCESS","previousRecordHash":"0000000000000000000000000000000000000000000000000000000000000000","recordHash":"...","signature":"..."}
{"sequenceNumber":2,"auditId":"audit-2","correlationId":"request-123","timestamp":"2026-05-25T00:00:01Z","action":"request-processed","outcome":"SUCCESS","previousRecordHash":"...","recordHash":"...","signature":"..."}
```

Each record contains:

- a sequence number
- audit and correlation identifiers
- actor, source, and destination information
- action and outcome
- message and category information
- optional parameters and attributes
- optional payload references
- the previous record hash
- the current record hash
- an optional signature

## Verification Model

Each record is hashed using the previous record hash and the canonical record content.

This creates a tamper-evident chain:

```text
record 1 -> record 2 -> record 3 -> record 4
```

If a record is modified, deleted, or inserted, verification fails.

The viewer marks each record as:

| Status | Meaning |
|---|---|
| `VALID` | Record hash, sequence, previous hash, and optional signature are valid |
| `INVALID` | Record failed verification |
| `NOT_VERIFIED` | Record was displayed without verification, if dump-only support is used |

Once a record fails verification, following records should be treated as invalid because the chain is no longer trustworthy.

## Main Classes

| Class | Purpose |
|---|---|
| `AuditJournalViewer` | Reads and verifies a journal file |
| `AuditJournalConsolePrinter` | Prints audit records as a console table |
| `AuditRecordView` | View model used by the console printer |
| `AuditRecordVerificationStatus` | Verification state for each displayed record |
| `AuditJournalViewCommand` | Command-line entry point for viewing/verifying a journal |
| `AuditVerifier` | Core audit-chain verifier |

`AuditVerifier` belongs in the audit/logging library because it validates the audit format itself. The viewer classes are convenience tools for displaying the audit trail.

## Command Line Usage

View and verify a journal:

```bash
maps-audit-view /var/lib/maps/audit/journal/2026-05-25/audit-2026-05-25-000001.jsonl --public-key /opt/maps/conf/audit-public-key.pem
```

View without a public key:

```bash
maps-audit-view /var/lib/maps/audit/journal/2026-05-25/audit-2026-05-25-000001.jsonl
```

When no public key is supplied, the viewer can still validate:

- sequence order
- previous hash linkage
- record hash integrity

It cannot validate signatures.

## Example Output

```text
Line     Seq      Status   Timestamp                    Correlation              Actor              Source             Action               Outcome      Message                                  Validation
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
1        1        VALID    2026-05-25T00:00:00Z         correlation-1            unit-test          source-system      first-action         SUCCESS      TestAuditMessages.TEST_AUDIT_EVENT       Valid
2        2        VALID    2026-05-25T00:00:01Z         correlation-2            unit-test          source-system      second-action        SUCCESS      TestAuditMessages.TEST_AUDIT_EVENT       Valid

Records: 2
Valid:   2
Invalid: 0
```

If a record is modified:

```text
Line     Seq      Status   Timestamp                    Correlation              Actor              Source             Action               Outcome      Message                                  Validation
------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
1        1        VALID    2026-05-25T00:00:00Z         correlation-1            unit-test          source-system      first-action         SUCCESS      TestAuditMessages.TEST_AUDIT_EVENT       Valid
2        2        INVALID  2026-05-25T00:00:01Z         correlation-2            unit-test          source-system      second-action        SUCCESS      TestAuditMessages.TEST_AUDIT_EVENT       Record hash mismatch

Records: 2
Valid:   1
Invalid: 1
```

## Typical Usage in Tests

```java
AuditJournalViewer auditJournalViewer = new AuditJournalViewer(
    (java.security.interfaces.EdECPublicKey) keyPair.getPublic()
);

List<AuditRecordView> records = auditJournalViewer.readAndVerify(
    auditJournal.getActiveJournalPath()
);

AuditJournalConsolePrinter auditJournalConsolePrinter = new AuditJournalConsolePrinter();
auditJournalConsolePrinter.print(records);
```

## Audit Guidance

Audit only events that matter.

Good audit events include:

- request received
- request interpreted or processed
- request accepted or rejected
- policy decision made
- safety decision made
- external command or request generated
- external command or request sent
- acknowledgement or response received
- state changed
- operation completed, failed, cancelled, or aborted
- configuration changed
- permission, authentication, or authorisation changed

Avoid auditing high-frequency updates. Use normal logging, metrics, or time-series storage for that.

For high-frequency systems, audit state transitions rather than every update.

Examples:

- status changed
- mode changed
- threshold crossed
- warning triggered
- failure detected
- acknowledgement received
- connection degraded or restored

## Correlation

Use `correlationId` to group related audit records.

Example:

```text
correlationId = requestId
```

For child operations, use the child operation identifier as the `correlationId` and the parent operation as `parentCorrelationId`.

Example:

```text
correlationId = childOperationId
parentCorrelationId = requestId
```

This allows the audit trail to be viewed either by the top-level request or by individual child operations.

Example structure:

```text
REQUEST-123
  REQUEST_RECEIVED
  REQUEST_PROCESSED
  RESPONSE_SENT
  CHILD_OPERATION_STARTED CHILD-001
  CHILD_OPERATION_COMPLETED CHILD-001
  REQUEST_COMPLETED SUCCESS
```

## Operational Notes

For important external actions, the audit event should be written before the action is performed.

Recommended order:

```text
receive request
interpret request
generate action
write audit record
flush audit journal
perform action
write result
record acknowledgement/status
```

If auditing fails for critical actions, the safest default is to reject or hold the action.

For non-critical or high-frequency paths, audit failure may be treated as degraded mode, depending on configuration.

## Design Principle

The audit viewer is only a presentation layer.

The source of truth is:

```text
append-only JSONL journal
+ hash chain
+ optional Ed25519 signatures
+ payload hashes
```

The viewer helps humans inspect the trail without forcing them to spelunk through raw JSON like some kind of compliance archaeologist.
