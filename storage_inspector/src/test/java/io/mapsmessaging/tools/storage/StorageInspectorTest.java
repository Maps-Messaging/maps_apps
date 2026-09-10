/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import org.junit.jupiter.api.Test;

class StorageInspectorTest {
  @Test
  void inspectValidAndCorruptStoresWithoutChangingInputs() throws Exception {
    StorageInspectorChecks.main(new String[0]);
  }

  @Test
  void preserveJsonAndBinaryPayloadsAndMessageMetadata() throws Exception {
    MessageExportChecks.main(new String[0]);
  }
}
