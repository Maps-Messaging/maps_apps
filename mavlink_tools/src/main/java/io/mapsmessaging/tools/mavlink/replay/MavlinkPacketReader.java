package io.mapsmessaging.tools.mavlink.replay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class MavlinkPacketReader {

  private static final int MAVLINK_1_MAGIC = 0xFE;
  private static final int MAVLINK_2_MAGIC = 0xFD;
  private static final int MAVLINK_2_SIGNED_FLAG = 0x01;

  public byte[] readPacket(InputStream inputStream) throws IOException {
    int magicByte = readUntilMagic(inputStream);
    if (magicByte < 0) {
      return null;
    }

    int payloadLength = readRequiredByte(inputStream);
    if (magicByte == MAVLINK_1_MAGIC) {
      return readMavlink1Packet(inputStream, magicByte, payloadLength);
    }
    return readMavlink2Packet(inputStream, magicByte, payloadLength);
  }

  public MavlinkPacketInfo inspect(byte[] packetData) throws IOException {
    if (packetData == null || packetData.length < 8) {
      throw new IOException("Packet is too short");
    }

    int magicByte = packetData[0] & 0xFF;
    int payloadLength = packetData[1] & 0xFF;
    if (magicByte == MAVLINK_1_MAGIC) {
      return inspectMavlink1(packetData, payloadLength);
    }
    if (magicByte == MAVLINK_2_MAGIC) {
      return inspectMavlink2(packetData, payloadLength);
    }
    throw new IOException("Unsupported MAVLink magic byte: 0x" + Integer.toHexString(magicByte));
  }

  private byte[] readMavlink1Packet(InputStream inputStream, int magicByte, int payloadLength) throws IOException {
    int packetLength = payloadLength + 8;
    byte[] packetData = new byte[packetLength];
    packetData[0] = (byte) magicByte;
    packetData[1] = (byte) payloadLength;
    readFully(inputStream, packetData, 2, packetLength - 2);
    return packetData;
  }

  private byte[] readMavlink2Packet(InputStream inputStream, int magicByte, int payloadLength) throws IOException {
    byte[] headerBuffer = new byte[8];
    readFully(inputStream, headerBuffer, 0, headerBuffer.length);

    int incompatFlags = headerBuffer[0] & 0xFF;
    boolean signedPacket = (incompatFlags & MAVLINK_2_SIGNED_FLAG) != 0;
    int packetLength = signedPacket ? payloadLength + 25 : payloadLength + 12;
    byte[] packetData = new byte[packetLength];
    packetData[0] = (byte) magicByte;
    packetData[1] = (byte) payloadLength;
    System.arraycopy(headerBuffer, 0, packetData, 2, headerBuffer.length);
    readFully(inputStream, packetData, 10, packetLength - 10);
    return packetData;
  }

  private MavlinkPacketInfo inspectMavlink1(byte[] packetData, int payloadLength) throws IOException {
    int expectedLength = payloadLength + 8;
    if (packetData.length != expectedLength) {
      throw new IOException("Invalid MAVLink 1 packet length, expected " + expectedLength + " but found " + packetData.length);
    }

    int lowCrcByte = packetData[packetData.length - 2] & 0xFF;
    int highCrcByte = packetData[packetData.length - 1] & 0xFF;
    return new MavlinkPacketInfo(
        1,
        payloadLength,
        packetData[2] & 0xFF,
        packetData[3] & 0xFF,
        packetData[4] & 0xFF,
        packetData[5] & 0xFF,
        false,
        packetData.length,
        lowCrcByte | (highCrcByte << 8)
    );
  }

  private MavlinkPacketInfo inspectMavlink2(byte[] packetData, int payloadLength) throws IOException {
    if (packetData.length < 12) {
      throw new IOException("Invalid MAVLink 2 packet length: " + packetData.length);
    }

    int incompatFlags = packetData[2] & 0xFF;
    boolean signedPacket = (incompatFlags & MAVLINK_2_SIGNED_FLAG) != 0;
    int expectedLength = signedPacket ? payloadLength + 25 : payloadLength + 12;
    if (packetData.length != expectedLength) {
      throw new IOException("Invalid MAVLink 2 packet length, expected " + expectedLength + " but found " + packetData.length);
    }

    int messageIdLow = packetData[7] & 0xFF;
    int messageIdMiddle = packetData[8] & 0xFF;
    int messageIdHigh = packetData[9] & 0xFF;
    int crcOffset = 10 + payloadLength;
    int lowCrcByte = packetData[crcOffset] & 0xFF;
    int highCrcByte = packetData[crcOffset + 1] & 0xFF;
    return new MavlinkPacketInfo(
        2,
        payloadLength,
        packetData[4] & 0xFF,
        packetData[5] & 0xFF,
        packetData[6] & 0xFF,
        messageIdLow | (messageIdMiddle << 8) | (messageIdHigh << 16),
        signedPacket,
        packetData.length,
        lowCrcByte | (highCrcByte << 8)
    );
  }

  private int readUntilMagic(InputStream inputStream) throws IOException {
    int value;
    while ((value = inputStream.read()) >= 0) {
      if (value == MAVLINK_1_MAGIC || value == MAVLINK_2_MAGIC) {
        return value;
      }
    }
    return -1;
  }

  private int readRequiredByte(InputStream inputStream) throws IOException {
    int value = inputStream.read();
    if (value < 0) {
      throw new EOFException("Unexpected end of stream");
    }
    return value;
  }

  private void readFully(InputStream inputStream, byte[] buffer, int offset, int length) throws IOException {
    int remainingLength = length;
    int currentOffset = offset;
    while (remainingLength > 0) {
      int bytesRead = inputStream.read(buffer, currentOffset, remainingLength);
      if (bytesRead < 0) {
        throw new EOFException("Unexpected end of stream");
      }
      currentOffset += bytesRead;
      remainingLength -= bytesRead;
    }
  }
}
