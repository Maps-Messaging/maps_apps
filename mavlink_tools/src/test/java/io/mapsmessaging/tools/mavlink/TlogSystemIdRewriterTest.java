package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketReader;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecord;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordReader;
import io.mapsmessaging.tools.mavlink.tlog.TlogRecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TlogSystemIdRewriterTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void rewritesOnlyFramesInsideTimeWindow() throws Exception {
    Path inputPath = temporaryDirectory.resolve("input.tlog");
    Path outputPath = temporaryDirectory.resolve("output.tlog");

    try (TlogRecordWriter writer = new TlogRecordWriter(inputPath)) {
      writer.write(new TlogRecord(1_000_000L, heartbeat(1, 1)));
      writer.write(new TlogRecord(601_000_000L, heartbeat(2, 1)));
    }

    int exitCode = new CommandLine(new TlogSystemIdRewriter()).execute(
        inputPath.toString(),
        outputPath.toString(),
        "1",
        "10",
        "--start-offset",
        "05:00"
    );

    assertEquals(0, exitCode);
    MavlinkPacketReader packetReader = new MavlinkPacketReader();
    try (TlogRecordReader reader = new TlogRecordReader(outputPath)) {
      assertEquals(1, packetReader.inspect(reader.read().packetData()).systemId());
      assertEquals(10, packetReader.inspect(reader.read().packetData()).systemId());
    }
  }

  private byte[] heartbeat(int sequence, int systemId) {
    byte[] packetData = new byte[17];
    packetData[0] = (byte) 0xFE;
    packetData[1] = 9;
    packetData[2] = (byte) sequence;
    packetData[3] = (byte) systemId;
    packetData[4] = 1;
    packetData[5] = 0;
    return packetData;
  }
}
