package io.mapsmessaging.tools.mavlink.tlog;

import io.mapsmessaging.mavlink.MavlinkMessageFormatLoader;
import io.mapsmessaging.mavlink.message.MessageRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MavlinkSystemIdPacketRewriterUnknownMessageTest {

  @Test
  void rejectsMessageMissingFromDialect() throws Exception {
    MessageRegistry messageRegistry = MavlinkMessageFormatLoader.getInstance().getDialectOrThrow("common").getRegistry();
    MavlinkSystemIdPacketRewriter rewriter = new MavlinkSystemIdPacketRewriter(messageRegistry);
    byte[] packetData = new byte[12];
    packetData[0] = (byte) 0xFD;
    packetData[5] = 1;
    packetData[7] = (byte) 0xFF;
    packetData[8] = (byte) 0xFF;
    packetData[9] = 0x7F;

    assertThrows(IOException.class, () -> rewriter.rewrite(packetData, 1, 10));
  }
}
