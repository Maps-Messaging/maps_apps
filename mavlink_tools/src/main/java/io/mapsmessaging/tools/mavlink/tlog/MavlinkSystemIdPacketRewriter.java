package io.mapsmessaging.tools.mavlink.tlog;

import io.mapsmessaging.mavlink.message.CompiledMessage;
import io.mapsmessaging.mavlink.message.MessageRegistry;
import io.mapsmessaging.mavlink.message.X25Crc;
import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketInfo;
import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketReader;

import java.io.IOException;

public class MavlinkSystemIdPacketRewriter {

  private final MessageRegistry messageRegistry;
  private final MavlinkPacketReader mavlinkPacketReader;

  public MavlinkSystemIdPacketRewriter(MessageRegistry messageRegistry) {
    this.messageRegistry = messageRegistry;
    this.mavlinkPacketReader = new MavlinkPacketReader();
  }

  public byte[] rewrite(byte[] packetData, int oldSystemId, int newSystemId) throws IOException {
    MavlinkPacketInfo packetInfo = mavlinkPacketReader.inspect(packetData);
    if (packetInfo.systemId() != oldSystemId) {
      return packetData;
    }
    if (packetInfo.signedPacket()) {
      throw new IOException("Cannot rewrite a signed MAVLink 2 frame without its signing key");
    }

    CompiledMessage compiledMessage = messageRegistry.getCompiledMessagesById().get(packetInfo.messageId());
    if (compiledMessage == null) {
      throw new IOException("Unknown MAVLink message ID " + packetInfo.messageId() + " in the selected dialect");
    }

    byte[] rewrittenPacket = packetData.clone();
    int systemIdOffset;
    int checksumOffset;
    int checksumInputLength;

    if (packetInfo.mavlinkVersion() == 1) {
      systemIdOffset = 3;
      checksumOffset = 6 + packetInfo.payloadLength();
      checksumInputLength = 5 + packetInfo.payloadLength();
    } else {
      systemIdOffset = 5;
      checksumOffset = 10 + packetInfo.payloadLength();
      checksumInputLength = 9 + packetInfo.payloadLength();
    }

    rewrittenPacket[systemIdOffset] = (byte) newSystemId;

    X25Crc crc = new X25Crc();
    crc.update(rewrittenPacket, 1, checksumInputLength);
    crc.update(compiledMessage.getCrcExtra() & 0xFF);
    int checksum = crc.getCrc();
    rewrittenPacket[checksumOffset] = (byte) (checksum & 0xFF);
    rewrittenPacket[checksumOffset + 1] = (byte) ((checksum >>> 8) & 0xFF);
    return rewrittenPacket;
  }
}
