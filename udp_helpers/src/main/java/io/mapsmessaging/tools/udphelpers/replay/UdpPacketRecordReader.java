package io.mapsmessaging.tools.udphelpers.replay;

import io.mapsmessaging.tools.udphelpers.common.UdpPacketRecord;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

public class UdpPacketRecordReader implements Closeable {

  private static final byte[] MAGIC = "MUDP".getBytes(StandardCharsets.US_ASCII);
  private static final int VERSION = 1;

  private final DataInputStream inputStream;

  public UdpPacketRecordReader(InputStream inputStream) throws IOException {
    this.inputStream = new DataInputStream(new BufferedInputStream(inputStream));

    readHeader();
  }

  public Optional<UdpPacketRecord> read() throws IOException {
    try {
      long timestampNanos = inputStream.readLong();

      int sourceAddressLength = inputStream.readInt();
      byte[] sourceAddressBytes = inputStream.readNBytes(sourceAddressLength);

      if (sourceAddressBytes.length != sourceAddressLength) {
        throw new EOFException("Unexpected end of file while reading source address");
      }

      int sourcePort = inputStream.readInt();

      int payloadLength = inputStream.readInt();
      byte[] payload = inputStream.readNBytes(payloadLength);

      if (payload.length != payloadLength) {
        throw new EOFException("Unexpected end of file while reading payload");
      }

      InetAddress sourceAddress = InetAddress.getByAddress(sourceAddressBytes);
      UdpPacketRecord udpPacketRecord = new UdpPacketRecord(
          timestampNanos,
          sourceAddress,
          sourcePort,
          payload
      );

      return Optional.of(udpPacketRecord);
    } catch (EOFException eofException) {
      return Optional.empty();
    }
  }

  private void readHeader() throws IOException {
    byte[] magic = inputStream.readNBytes(MAGIC.length);

    if (!Arrays.equals(MAGIC, magic)) {
      throw new IOException("Invalid UDP helper capture file magic");
    }

    int version = inputStream.readInt();

    if (version != VERSION) {
      throw new IOException("Unsupported UDP helper capture file version: " + version);
    }
  }

  @Override
  public void close() throws IOException {
    inputStream.close();
  }
}