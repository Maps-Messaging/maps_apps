package io.mapsmessaging.tools.udphelpers.capture;

import io.mapsmessaging.tools.udphelpers.common.UdpPacketRecord;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class UdpPacketRecordWriter implements Closeable {

  private static final byte[] MAGIC = "MUDP".getBytes(StandardCharsets.US_ASCII);
  private static final int VERSION = 1;

  private final DataOutputStream outputStream;
  private final boolean flushEachPacket;

  public UdpPacketRecordWriter(OutputStream outputStream, boolean flushEachPacket) throws IOException {
    this.outputStream = new DataOutputStream(new BufferedOutputStream(outputStream));
    this.flushEachPacket = flushEachPacket;

    writeHeader();
  }

  public void write(UdpPacketRecord udpPacketRecord) throws IOException {
    byte[] sourceAddressBytes = udpPacketRecord.getSourceAddress().getAddress();
    byte[] payload = udpPacketRecord.getPayload();

    outputStream.writeLong(udpPacketRecord.getTimestampNanos());
    outputStream.writeInt(sourceAddressBytes.length);
    outputStream.write(sourceAddressBytes);
    outputStream.writeInt(udpPacketRecord.getSourcePort());
    outputStream.writeInt(payload.length);
    outputStream.write(payload);

    if (flushEachPacket) {
      outputStream.flush();
    }
  }

  private void writeHeader() throws IOException {
    outputStream.write(MAGIC);
    outputStream.writeInt(VERSION);
  }

  @Override
  public void close() throws IOException {
    outputStream.flush();
    outputStream.close();
  }
}