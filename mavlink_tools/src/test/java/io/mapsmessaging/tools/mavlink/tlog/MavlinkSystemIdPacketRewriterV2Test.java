package io.mapsmessaging.tools.mavlink.tlog;

import io.mapsmessaging.mavlink.MavlinkMessageFormatLoader;
import io.mapsmessaging.mavlink.message.CompiledMessage;
import io.mapsmessaging.mavlink.message.MessageRegistry;
import io.mapsmessaging.mavlink.message.X25Crc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavlinkSystemIdPacketRewriterV2Test {

  @Test
  void rewritesUnsignedMavlinkTwoSystemIdAndChecksum() throws Exception {
    MessageRegistry messageRegistry = MavlinkMessageFormatLoader.getInstance().getDialectOrThrow("common").getRegistry();
    MavlinkSystemIdPacketRewriter rewriter = new MavlinkSystemIdPacketRewriter(messageRegistry);
    byte[] packetData = new byte[21];
    packetData[0] = (byte) 0xFD;
    packetData[1] = 9;
    packetData[4] = 1;
    packetData[5] = 1;
    packetData[6] = 1;

    byte[] rewrittenPacket = rewriter.rewrite(packetData, 1, 10);

    assertEquals(10, rewrittenPacket[5] & 0xFF);
    CompiledMessage compiledMessage = messageRegistry.getCompiledMessagesById().get(0);
    X25Crc crc = new X25Crc();
    crc.update(rewrittenPacket, 1, 18);
    crc.update(compiledMessage.getCrcExtra() & 0xFF);
    int expectedChecksum = crc.getCrc();
    int actualChecksum = (rewrittenPacket[19] & 0xFF) | ((rewrittenPacket[20] & 0xFF) << 8);
    assertEquals(expectedChecksum, actualChecksum);
  }
}
