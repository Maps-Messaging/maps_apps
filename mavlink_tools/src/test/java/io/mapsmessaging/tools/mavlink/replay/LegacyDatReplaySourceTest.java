package io.mapsmessaging.tools.mavlink.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyDatReplaySourceTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void readsLegacyRecords() throws Exception {
    byte[] packet = new byte[]{(byte) 0xFE, 0, 1, 2, 3, 4, 5, 6};
    Path capturePath = temporaryDirectory.resolve("capture.dat");
    Files.writeString(capturePath, "125," + Base64.getEncoder().encodeToString(packet) + System.lineSeparator());

    try (LegacyDatReplaySource replaySource = new LegacyDatReplaySource(capturePath)) {
      ReplayFrame replayFrame = replaySource.nextFrame();

      assertEquals(125_000_000L, replayFrame.delayNanos());
      assertArrayEquals(packet, replayFrame.packetData());
      assertNull(replaySource.nextFrame());
    }
  }
}
