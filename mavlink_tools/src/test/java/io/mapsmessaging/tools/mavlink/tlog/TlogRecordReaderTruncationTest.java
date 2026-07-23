package io.mapsmessaging.tools.mavlink.tlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TlogRecordReaderTruncationTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void rejectsTruncatedTimestamp() throws Exception {
    Path tlogPath = temporaryDirectory.resolve("truncated.tlog");
    Files.write(tlogPath, new byte[]{0, 1, 2});

    try (TlogRecordReader reader = new TlogRecordReader(tlogPath)) {
      assertThrows(EOFException.class, reader::read);
    }
  }
}
