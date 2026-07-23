package io.mapsmessaging.tools.mavlink.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RawMavlinkReplaySourceTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void scansRawMavlinkFrames() throws Exception {
    Path capturePath = temporaryDirectory.resolve("flight.raw");
    byte[] firstPacket = new byte[]{(byte) 0xFE, 0, 1, 2, 3, 4, 5, 6};
    byte[] secondPacket = new byte[]{(byte) 0xFE, 0, 2, 2, 3, 5, 7, 8};
    byte[] stream = new byte[firstPacket.length + secondPacket.length + 2];
    stream[0] = 0;
    System.arraycopy(firstPacket, 0, stream, 1, firstPacket.length);
    stream[firstPacket.length + 1] = 0;
    System.arraycopy(secondPacket, 0, stream, firstPacket.length + 2, secondPacket.length);
    Files.write(capturePath, stream);

    try (RawMavlinkReplaySource replaySource = new RawMavlinkReplaySource(capturePath, 25_000L)) {
      ReplayFrame firstFrame = replaySource.nextFrame();
      ReplayFrame secondFrame = replaySource.nextFrame();

      assertArrayEquals(firstPacket, firstFrame.packetData());
      assertEquals(0, firstFrame.delayNanos());
      assertArrayEquals(secondPacket, secondFrame.packetData());
      assertEquals(25_000L, secondFrame.delayNanos());
      assertNull(replaySource.nextFrame());
    }
  }
}
