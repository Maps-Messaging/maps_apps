package io.mapsmessaging.tools.mavlink.tlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TlogRecordReaderWriterTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void preservesTimestampAndPacketBytes() throws Exception {
    Path tlogPath = temporaryDirectory.resolve("capture.tlog");
    byte[] packetData = new byte[]{(byte) 0xFE, 0, 1, 2, 3, 4, 5, 6};

    try (TlogRecordWriter writer = new TlogRecordWriter(tlogPath)) {
      writer.write(new TlogRecord(1_234_567L, packetData));
    }

    try (TlogRecordReader reader = new TlogRecordReader(tlogPath)) {
      TlogRecord record = reader.read();
      assertEquals(1_234_567L, record.timestampMicros());
      assertArrayEquals(packetData, record.packetData());
      assertNull(reader.read());
    }
  }
}
