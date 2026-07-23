package io.mapsmessaging.tools.mavlink.tlog;

import io.mapsmessaging.mavlink.MavlinkMessageFormatLoader;
import io.mapsmessaging.mavlink.message.MessageRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class MavlinkSystemIdPacketRewriterNoMatchTest {

  @Test
  void returnsOriginalPacketWhenSystemIdDoesNotMatch() throws Exception {
    MessageRegistry messageRegistry = MavlinkMessageFormatLoader.getInstance().getDialectOrThrow("common").getRegistry();
    MavlinkSystemIdPacketRewriter rewriter = new MavlinkSystemIdPacketRewriter(messageRegistry);
    byte[] packetData = new byte[]{(byte) 0xFE, 0, 1, 2, 1, 0, 0, 0};

    assertSame(packetData, rewriter.rewrite(packetData, 1, 10));
  }
}
