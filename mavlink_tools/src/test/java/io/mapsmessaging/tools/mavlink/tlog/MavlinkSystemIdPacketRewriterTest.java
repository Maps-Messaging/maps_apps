package io.mapsmessaging.tools.mavlink.tlog;

import io.mapsmessaging.mavlink.MavlinkMessageFormatLoader;
import io.mapsmessaging.mavlink.message.CompiledMessage;
import io.mapsmessaging.mavlink.message.MessageRegistry;
import io.mapsmessaging.mavlink.message.X25Crc;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MavlinkSystemIdPacketRewriterTest {

  @Test
  void rewritesMavlinkOneSystemIdAndChecksum() throws Exception {
    MessageRegistry messageRegistry = MavlinkMessageFormatLoader.getInstance().getDialectOrThrow("common").getRegistry();
    MavlinkSystemIdPacketRewriter rewriter = new MavlinkSystemIdPacketRewriter(messageRegistry);
    byte[] packetData = mavlinkOneHeartbeat(1);

    byte[] rewrittenPacket = rewriter.rewrite(packetData, 1, 10);

    assertEquals(1, packetData[3] & 0xFF);
    assertEquals(10, rewrittenPacket[3] & 0xFF);

    CompiledMessage compiledMessage = messageRegistry.getCompiledMessagesById().get(0);
    X25Crc crc = new X25Crc();
    crc.update(rewrittenPacket, 1, 14);
    crc.update(compiledMessage.getCrcExtra() & 0xFF);
    int expectedChecksum = crc.getCrc();
    int actualChecksum = (rewrittenPacket[15] & 0xFF) | ((rewrittenPacket[16] & 0xFF) << 8);
    assertEquals(expectedChecksum, actualChecksum);
  }

  @Test
  void rejectsSignedMavlinkTwoFrame() throws Exception {
    MessageRegistry messageRegistry = MavlinkMessageFormatLoader.getInstance().getDialectOrThrow("common").getRegistry();
    MavlinkSystemIdPacketRewriter rewriter = new MavlinkSystemIdPacketRewriter(messageRegistry);
    byte[] packetData = new byte[25];
    packetData[0] = (byte) 0xFD;
    packetData[2] = 1;
    packetData[5] = 1;

    assertThrows(IOException.class, () -> rewriter.rewrite(packetData, 1, 10));
  }

  private byte[] mavlinkOneHeartbeat(int systemId) {
    byte[] packetData = new byte[17];
    packetData[0] = (byte) 0xFE;
    packetData[1] = 9;
    packetData[2] = 1;
    packetData[3] = (byte) systemId;
    packetData[4] = 1;
    packetData[5] = 0;
    return packetData;
  }
}
