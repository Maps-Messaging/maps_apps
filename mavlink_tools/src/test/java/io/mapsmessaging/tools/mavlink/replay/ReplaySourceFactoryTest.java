package io.mapsmessaging.tools.mavlink.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplaySourceFactoryTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void detectsSupportedExtensions() throws Exception {
    ReplaySourceFactory replaySourceFactory = new ReplaySourceFactory();

    Path tlogPath = Files.createFile(temporaryDirectory.resolve("flight.tlog"));
    Path datPath = Files.createFile(temporaryDirectory.resolve("flight.dat"));
    Path rawPath = Files.createFile(temporaryDirectory.resolve("flight.raw"));

    assertEquals(ReplayFormat.TLOG, replaySourceFactory.detect(tlogPath));
    assertEquals(ReplayFormat.LEGACY_DAT, replaySourceFactory.detect(datPath));
    assertEquals(ReplayFormat.RAW, replaySourceFactory.detect(rawPath));
  }

  @Test
  void detectsMudpHeaderWithoutExtension() throws Exception {
    ReplaySourceFactory replaySourceFactory = new ReplaySourceFactory();
    Path capturePath = temporaryDirectory.resolve("capture.bin");
    Files.write(capturePath, new byte[]{'M', 'U', 'D', 'P', 0, 0, 0, 1});

    assertEquals(ReplayFormat.MUDP, replaySourceFactory.detect(capturePath));
  }

  @Test
  void doesNotAutoDetectMissionPlannerRlog() throws Exception {
    ReplaySourceFactory replaySourceFactory = new ReplaySourceFactory();
    Path capturePath = Files.createFile(temporaryDirectory.resolve("flight.rlog"));

    assertThrows(IOException.class, () -> replaySourceFactory.detect(capturePath));
  }
}
