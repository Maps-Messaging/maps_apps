package io.mapsmessaging.tools.mavlink.replay;

public record MavlinkPacketInfo(
    int mavlinkVersion,
    int payloadLength,
    int sequence,
    int systemId,
    int componentId,
    int messageId,
    boolean signedPacket,
    int packetLength,
    int crc
) {
}
