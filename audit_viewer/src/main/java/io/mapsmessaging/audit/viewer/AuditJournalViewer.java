/*
 *
 *  Copyright [ 2020 - 2024 ] Matthew Buckton
 *  Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      https://commonsclause.com/
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.mapsmessaging.audit.viewer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.mapsmessaging.audit.AuditCrypto;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.EdECPublicKey;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class AuditJournalViewer {

  private static final String GENESIS_HASH =
      "0000000000000000000000000000000000000000000000000000000000000000";

  private static final Pattern JOURNAL_FILE_PATTERN =
      Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})-(\\d{6})\\.jsonl");

  private final Gson gson;
  private final AuditCrypto auditCrypto;
  private final EdECPublicKey publicKey;

  public AuditJournalViewer(EdECPublicKey publicKey) {
    this.publicKey = publicKey;
    this.gson = new GsonBuilder()
        .disableHtmlEscaping()
        .create();
    this.auditCrypto = new AuditCrypto();
  }

  public List<AuditRecordView> readAndVerify(Path journalPath) throws IOException {
    return readAndVerify(List.of(journalPath));
  }

  public List<AuditRecordView> readAndVerifyJournalRoot(Path journalRoot) throws IOException {
    return readAndVerify(discoverJournalFiles(journalRoot));
  }

  public List<AuditRecordView> readAndVerify(List<Path> journalPaths) throws IOException {
    List<AuditRecordView> records = new ArrayList<>();

    VerificationState verificationState = new VerificationState(
        1,
        GENESIS_HASH,
        false
    );

    for (Path journalPath : journalPaths) {
      verificationState = readAndVerifyJournal(
          journalPath,
          records,
          verificationState
      );
    }

    return records;
  }

  private VerificationState readAndVerifyJournal(
      Path journalPath,
      List<AuditRecordView> records,
      VerificationState verificationState
  ) throws IOException {
    long lineNumber = 0;
    long expectedSequenceNumber = verificationState.expectedSequenceNumber();
    String previousRecordHash = verificationState.previousRecordHash();
    boolean chainAlreadyBroken = verificationState.chainAlreadyBroken();

    try (BufferedReader bufferedReader = Files.newBufferedReader(journalPath, StandardCharsets.UTF_8)) {
      String line = bufferedReader.readLine();

      while (line != null) {
        lineNumber++;

        if (!line.isBlank()) {
          AuditRecordView recordView = verifyLine(
              journalPath,
              line,
              lineNumber,
              expectedSequenceNumber,
              previousRecordHash,
              chainAlreadyBroken
          );

          records.add(recordView);

          if (recordView.getStatus() == AuditRecordVerificationStatus.VALID) {
            previousRecordHash = recordView.getRecordHash();
            expectedSequenceNumber++;
          } else {
            chainAlreadyBroken = true;

            if (recordView.getSequenceNumber() == expectedSequenceNumber) {
              expectedSequenceNumber++;
            }

            if (recordView.getRecordHash() != null && !recordView.getRecordHash().isBlank()) {
              previousRecordHash = recordView.getRecordHash();
            }
          }
        }

        line = bufferedReader.readLine();
      }
    }

    return new VerificationState(
        expectedSequenceNumber,
        previousRecordHash,
        chainAlreadyBroken
    );
  }

  private AuditRecordView verifyLine(
      Path journalPath,
      String line,
      long lineNumber,
      long expectedSequenceNumber,
      String previousRecordHash,
      boolean chainAlreadyBroken
  ) {
    try {
      JsonObject journalObject = gson.fromJson(line, JsonObject.class);

      AuditRecordView recordView = createRecordView(
          journalPath,
          lineNumber,
          journalObject
      );

      if (chainAlreadyBroken) {
        recordView.setStatus(AuditRecordVerificationStatus.INVALID);
        recordView.setValidationMessage("Previous record in chain was invalid");
        return recordView;
      }

      if (!journalObject.has("sequenceNumber")) {
        return invalid(recordView, "Missing sequenceNumber");
      }

      if (!journalObject.has("previousRecordHash")) {
        return invalid(recordView, "Missing previousRecordHash");
      }

      if (!journalObject.has("recordHash")) {
        return invalid(recordView, "Missing recordHash");
      }

      long sequenceNumber = journalObject.get("sequenceNumber").getAsLong();
      String storedPreviousRecordHash = journalObject.get("previousRecordHash").getAsString();
      String storedRecordHash = journalObject.get("recordHash").getAsString();

      if (sequenceNumber != expectedSequenceNumber) {
        return invalid(
            recordView,
            "Expected sequence " + expectedSequenceNumber + " but found " + sequenceNumber
        );
      }

      if (!previousRecordHash.equals(storedPreviousRecordHash)) {
        return invalid(
            recordView,
            "Previous hash mismatch"
        );
      }

      String storedSignature = journalObject.has("signature")
          ? journalObject.get("signature").getAsString()
          : "";

      journalObject.remove("recordHash");
      journalObject.remove("signature");

      String canonicalJson = gson.toJson(journalObject);
      String calculatedHash = auditCrypto.sha256Hex(canonicalJson);

      if (!calculatedHash.equals(storedRecordHash)) {
        return invalid(
            recordView,
            "Record hash mismatch"
        );
      }

      if (publicKey != null && storedSignature != null && !storedSignature.isBlank()) {
        boolean signatureValid = auditCrypto.verifySignature(
            publicKey,
            storedRecordHash,
            storedSignature
        );

        if (!signatureValid) {
          return invalid(
              recordView,
              "Signature mismatch"
          );
        }
      }

      recordView.setStatus(AuditRecordVerificationStatus.VALID);
      recordView.setValidationMessage("Valid");

      return recordView;
    } catch (Exception exception) {
      return AuditRecordView.builder()
          .journalPath(journalPath.toString())
          .journalFileName(journalPath.getFileName().toString())
          .lineNumber(lineNumber)
          .sequenceNumber(-1)
          .status(AuditRecordVerificationStatus.INVALID)
          .validationMessage("Invalid JSON or unreadable audit record: " + exception.getMessage())
          .build();
    }
  }

  private AuditRecordView createRecordView(
      Path journalPath,
      long lineNumber,
      JsonObject journalObject
  ) {
    return AuditRecordView.builder()
        .journalPath(journalPath.toString())
        .journalFileName(journalPath.getFileName().toString())
        .lineNumber(lineNumber)
        .sequenceNumber(getLong(journalObject, "sequenceNumber"))
        .timestamp(getString(journalObject, "timestamp"))
        .correlationId(getString(journalObject, "correlationId"))
        .parentCorrelationId(getString(journalObject, "parentCorrelationId"))
        .actor(getString(journalObject, "actor"))
        .source(getString(journalObject, "source"))
        .destination(getString(journalObject, "destination"))
        .action(getString(journalObject, "action"))
        .outcome(getString(journalObject, "outcome"))
        .categoryDivision(getString(journalObject, "categoryDivision"))
        .categoryDescription(getString(journalObject, "categoryDescription"))
        .messageCode(getString(journalObject, "messageCode"))
        .message(getString(journalObject, "message"))
        .recordHash(getString(journalObject, "recordHash"))
        .previousRecordHash(getString(journalObject, "previousRecordHash"))
        .status(AuditRecordVerificationStatus.INVALID)
        .validationMessage("Not verified")
        .build();
  }

  private List<Path> discoverJournalFiles(Path journalRoot) throws IOException {
    if (!Files.exists(journalRoot)) {
      return List.of();
    }

    try (Stream<Path> pathStream = Files.walk(journalRoot)) {
      return pathStream
          .filter(Files::isRegularFile)
          .map(this::toJournalFile)
          .filter(journalFile -> journalFile != null)
          .sorted(Comparator
              .comparing(JournalFile::localDate)
              .thenComparingInt(JournalFile::index))
          .map(JournalFile::path)
          .toList();
    }
  }

  private JournalFile toJournalFile(Path path) {
    String fileName = path.getFileName().toString();
    Matcher matcher = JOURNAL_FILE_PATTERN.matcher(fileName);

    if (!matcher.matches()) {
      return null;
    }

    LocalDate localDate = LocalDate.parse(matcher.group(1));
    int index = Integer.parseInt(matcher.group(2));

    return new JournalFile(path, localDate, index);
  }

  private AuditRecordView invalid(AuditRecordView recordView, String validationMessage) {
    recordView.setStatus(AuditRecordVerificationStatus.INVALID);
    recordView.setValidationMessage(validationMessage);
    return recordView;
  }

  private String getString(JsonObject jsonObject, String name) {
    if (!jsonObject.has(name) || jsonObject.get(name).isJsonNull()) {
      return "";
    }

    return jsonObject.get(name).getAsString();
  }

  private long getLong(JsonObject jsonObject, String name) {
    if (!jsonObject.has(name) || jsonObject.get(name).isJsonNull()) {
      return -1;
    }

    return jsonObject.get(name).getAsLong();
  }

  private record VerificationState(
      long expectedSequenceNumber,
      String previousRecordHash,
      boolean chainAlreadyBroken
  ) {
  }

  private record JournalFile(
      Path path,
      LocalDate localDate,
      int index
  ) {
  }
}