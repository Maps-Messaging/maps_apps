package io.mapsmessaging.tools.mavlink.replay;

import io.mapsmessaging.tools.udphelpers.capture.UdpPacketRecordWriter;
import io.mapsmessaging.tools.udphelpers.common.UdpPacketRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MudpReplaySourceTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void readsMapsUdpCaptureRecords() throws Exception {
    Path capturePath = temporaryDirectory.resolve("flight.mudp");
    byte[] firstPacket = new byte[]{(byte) 0xFE, 0, 1, 2, 3, 4, 5, 6};
    byte[] secondPacket = new byte[]{(byte) 0xFE, 0, 2, 2, 3, 5, 7, 8};
    InetAddress sourceAddress = InetAddress.getLoopbackAddress();

    try (
        OutputStream outputStream = Files.newOutputStream(capturePath);
        UdpPacketRecordWriter writer = new UdpPacketRecordWriter(outputStream, true)
    ) {
      writer.write(new UdpPacketRecord(10_000L, sourceAddress, 14550, firstPacket));
      writer.write(new UdpPacketRecord(60_000L, sourceAddress, 14550, secondPacket));
    }

    try (MudpReplaySource replaySource = new MudpReplaySource(capturePath)) {
      ReplayFrame firstFrame = replaySource.nextFrame();
      ReplayFrame secondFrame = replaySource.nextFrame();

      assertArrayEquals(firstPacket, firstFrame.packetData());
      assertEquals(0, firstFrame.delayNanos());
      assertArrayEquals(secondPacket, secondFrame.packetData());
      assertEquals(50_000L, secondFrame.delayNanos());
      assertNull(replaySource.nextFrame());
    }
  }
}
