package io.mapsmessaging.tools.mavlink.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplayTimelineTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void mergesRelativeInputsByTimeline() throws Exception {
    Path firstPath = temporaryDirectory.resolve("a.dat");
    Path secondPath = temporaryDirectory.resolve("b.dat");
    String packet = Base64.getEncoder().encodeToString(new byte[]{(byte) 0xFE, 0, 1, 2, 3, 4, 5, 6});

    Files.writeString(firstPath, "0," + packet + "\n100," + packet + "\n");
    Files.writeString(secondPath, "0," + packet + "\n50," + packet + "\n");

    try (ReplayTimeline replayTimeline = new ReplayTimeline(List.of(firstPath, secondPath), ReplayFormat.AUTO, 0)) {
      assertEquals(0, replayTimeline.nextFrame().timelineNanos());
      assertEquals(0, replayTimeline.nextFrame().timelineNanos());
      assertEquals(50_000_000L, replayTimeline.nextFrame().timelineNanos());
      assertEquals(100_000_000L, replayTimeline.nextFrame().timelineNanos());
      assertNull(replayTimeline.nextFrame());
    }
  }
}
