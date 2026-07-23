package io.mapsmessaging.tools.mavlink.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlogReplaySourceTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void readsTimestampedFrames() throws Exception {
    Path capturePath = temporaryDirectory.resolve("flight.tlog");
    byte[] firstPacket = new byte[]{(byte) 0xFE, 0, 1, 2, 3, 4, 5, 6};
    byte[] secondPacket = new byte[]{(byte) 0xFE, 0, 2, 2, 3, 5, 7, 8};

    try (DataOutputStream outputStream = new DataOutputStream(Files.newOutputStream(capturePath))) {
      outputStream.writeLong(1_000_000L);
      outputStream.write(firstPacket);
      outputStream.writeLong(1_002_500L);
      outputStream.write(secondPacket);
    }

    try (TlogReplaySource replaySource = new TlogReplaySource(capturePath)) {
      ReplayFrame firstFrame = replaySource.nextFrame();
      ReplayFrame secondFrame = replaySource.nextFrame();

      assertArrayEquals(firstPacket, firstFrame.packetData());
      assertEquals(0, firstFrame.delayNanos());
      assertArrayEquals(secondPacket, secondFrame.packetData());
      assertEquals(2_500_000L, secondFrame.delayNanos());
      assertTrue(replaySource.usesSourceTimeline());
      assertNull(replaySource.nextFrame());
    }
  }

  @Test
  void rejectsTruncatedTimestamp() throws Exception {
    Path capturePath = temporaryDirectory.resolve("truncated.tlog");
    Files.write(capturePath, new byte[]{0, 1, 2});

    try (TlogReplaySource replaySource = new TlogReplaySource(capturePath)) {
      assertThrows(EOFException.class, replaySource::nextFrame);
    }
  }
}
