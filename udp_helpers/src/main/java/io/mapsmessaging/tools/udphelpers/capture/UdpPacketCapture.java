package io.mapsmessaging.tools.udphelpers.capture;

import io.mapsmessaging.tools.udphelpers.common.UdpPacketRecord;
import io.mapsmessaging.tools.udphelpers.utils.HexFormatHelper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpPacketCapture {

  private final UdpCaptureArguments arguments;
  private volatile boolean running;

  public UdpPacketCapture(UdpCaptureArguments arguments) {
    this.arguments = arguments;
  }

  public void run(UdpPacketRecordWriter udpPacketRecordWriter) throws IOException {
    InetAddress bindAddress = InetAddress.getByName(arguments.getBindAddress());

    try (DatagramSocket datagramSocket = new DatagramSocket(arguments.getPort(), bindAddress)) {
      running = true;

      while (running) {
        byte[] packetBuffer = new byte[arguments.getBufferSize()];
        DatagramPacket datagramPacket = new DatagramPacket(packetBuffer, packetBuffer.length);

        datagramSocket.receive(datagramPacket);

        byte[] payload = new byte[datagramPacket.getLength()];
        System.arraycopy(datagramPacket.getData(), datagramPacket.getOffset(), payload, 0, datagramPacket.getLength());

        UdpPacketRecord udpPacketRecord = new UdpPacketRecord(
            System.nanoTime(),
            datagramPacket.getAddress(),
            datagramPacket.getPort(),
            payload
        );

        udpPacketRecordWriter.write(udpPacketRecord);

        if (!arguments.isQuiet()) {
          logPacket(udpPacketRecord);
        }
      }
    }
  }

  public void stop() {
    running = false;
  }

  private void logPacket(UdpPacketRecord udpPacketRecord) {
    StringBuilder stringBuilder = new StringBuilder();

    stringBuilder
        .append("captured ")
        .append(udpPacketRecord.getPayload().length)
        .append(" bytes from ")
        .append(udpPacketRecord.getSourceAddress().getHostAddress())
        .append(":")
        .append(udpPacketRecord.getSourcePort());

    if (arguments.isHexDump()) {
      stringBuilder
          .append(" ")
          .append(HexFormatHelper.toHex(udpPacketRecord.getPayload()));
    }

    System.out.println(stringBuilder);
  }
}