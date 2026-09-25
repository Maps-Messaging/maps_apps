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

import com.google.gson.JsonObject;
import java.nio.file.Path;

public record AuditPayloadEvidence(
    JsonObject reference,
    Path path,
    boolean present,
    boolean pathValid,
    boolean sha256Valid,
    String calculatedSha256,
    long size,
    String renderedData,
    String validationMessage
) {
}
