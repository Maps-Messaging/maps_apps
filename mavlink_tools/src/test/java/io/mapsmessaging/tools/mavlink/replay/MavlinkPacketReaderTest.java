package io.mapsmessaging.tools.mavlink.replay;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkPacketReaderTest {

  private final MavlinkPacketReader mavlinkPacketReader = new MavlinkPacketReader();

  @Test
  void inspectsMavlinkOnePacket() throws Exception {
    byte[] packet = new byte[]{(byte) 0xFE, 0, 7, 3, 1, 42, 0x34, 0x12};

    MavlinkPacketInfo packetInfo = mavlinkPacketReader.inspect(packet);

    assertEquals(1, packetInfo.mavlinkVersion());
    assertEquals(7, packetInfo.sequence());
    assertEquals(3, packetInfo.systemId());
    assertEquals(1, packetInfo.componentId());
    assertEquals(42, packetInfo.messageId());
    assertEquals(0x1234, packetInfo.crc());
    assertFalse(packetInfo.signedPacket());
  }

  @Test
  void inspectsSignedMavlinkTwoPacket() throws Exception {
    byte[] packet = new byte[25];
    packet[0] = (byte) 0xFD;
    packet[1] = 0;
    packet[2] = 1;
    packet[4] = 9;
    packet[5] = 10;
    packet[6] = 2;
    packet[7] = 0x34;
    packet[8] = 0x12;
    packet[10] = 0x78;
    packet[11] = 0x56;

    MavlinkPacketInfo packetInfo = mavlinkPacketReader.inspect(packet);

    assertEquals(2, packetInfo.mavlinkVersion());
    assertEquals(9, packetInfo.sequence());
    assertEquals(10, packetInfo.systemId());
    assertEquals(2, packetInfo.componentId());
    assertEquals(0x1234, packetInfo.messageId());
    assertEquals(0x5678, packetInfo.crc());
    assertTrue(packetInfo.signedPacket());
  }

  @Test
  void readsPacketAfterNoise() throws Exception {
    byte[] packet = new byte[]{(byte) 0xFE, 0, 1, 2, 3, 4, 5, 6};
    byte[] stream = new byte[]{0, 1, 2, packet[0], packet[1], packet[2], packet[3], packet[4], packet[5], packet[6], packet[7]};

    assertArrayEquals(packet, mavlinkPacketReader.readPacket(new ByteArrayInputStream(stream)));
  }

  @Test
  void rejectsTruncatedPacket() {
    byte[] packet = new byte[]{(byte) 0xFE, 2, 1, 2};

    assertThrows(EOFException.class, () -> mavlinkPacketReader.readPacket(new ByteArrayInputStream(packet)));
  }
}
