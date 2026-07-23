package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.tlog.TlogRecord;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordReader;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TlogTailExtractorTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void extractsTailWithoutChangingPacketsOrTimestamps() throws Exception {
    Path inputPath = temporaryDirectory.resolve("input.tlog");
    Path outputPath = temporaryDirectory.resolve("output.tlog");
    byte[] packetOne = packet(1);
    byte[] packetTwo = packet(2);
    byte[] packetThree = packet(3);

    try (TlogRecordWriter writer = new TlogRecordWriter(inputPath)) {
      writer.write(new TlogRecord(1_000_000L, packetOne));
      writer.write(new TlogRecord(601_000_000L, packetTwo));
      writer.write(new TlogRecord(1_801_000_000L, packetThree));
    }

    int exitCode = new CommandLine(new TlogTailExtractor()).execute(
        inputPath.toString(),
        outputPath.toString(),
        "20:00"
    );

    assertEquals(0, exitCode);
    try (TlogRecordReader reader = new TlogRecordReader(outputPath)) {
      TlogRecord firstRecord = reader.read();
      TlogRecord secondRecord = reader.read();

      assertEquals(601_000_000L, firstRecord.timestampMicros());
      assertArrayEquals(packetTwo, firstRecord.packetData());
      assertEquals(1_801_000_000L, secondRecord.timestampMicros());
      assertArrayEquals(packetThree, secondRecord.packetData());
      assertNull(reader.read());
    }
  }

  private byte[] packet(int sequence) {
    return new byte[]{(byte) 0xFE, 0, (byte) sequence, 1, 1, 0, 0, 0};
  }
}
