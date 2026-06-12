package io.mapsmessaging.tools.udphelpers.replay;

import io.mapsmessaging.tools.udphelpers.common.UdpPacketRecord;
import io.mapsmessaging.tools.udphelpers.utils.HexFormatHelper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpPacketReplay {

  private final UdpReplayArguments arguments;

  public UdpPacketReplay(UdpReplayArguments arguments) {
    this.arguments = arguments;
  }

  public void run(UdpPacketRecordReader udpPacketRecordReader) throws IOException, InterruptedException {
    InetAddress targetAddress = InetAddress.getByName(arguments.getTargetAddress());

    try (DatagramSocket datagramSocket = new DatagramSocket()) {
      UdpPacketRecord previousUdpPacketRecord = null;

      while (true) {
        UdpPacketRecord udpPacketRecord = udpPacketRecordReader.read().orElse(null);

        if (udpPacketRecord == null) {
          break;
        }

        delayIfRequired(previousUdpPacketRecord, udpPacketRecord);

        DatagramPacket datagramPacket = new DatagramPacket(
            udpPacketRecord.getPayload(),
            udpPacketRecord.getPayload().length,
            targetAddress,
            arguments.getTargetPort()
        );

        datagramSocket.send(datagramPacket);

        if (!arguments.isQuiet()) {
          logPacket(udpPacketRecord);
        }

        previousUdpPacketRecord = udpPacketRecord;
      }
    }
  }

  private void delayIfRequired(
      UdpPacketRecord previousUdpPacketRecord,
      UdpPacketRecord udpPacketRecord
  ) throws InterruptedException {
    if (!arguments.isPreserveTiming()) {
      return;
    }

    if (previousUdpPacketRecord == null) {
      return;
    }

    long delayNanos = udpPacketRecord.getTimestampNanos() - previousUdpPacketRecord.getTimestampNanos();

    if (delayNanos <= 0) {
      return;
    }

    double speed = arguments.getSpeed();

    if (speed <= 0) {
      speed = 1.0;
    }

    long adjustedDelayNanos = (long) (delayNanos / speed);
    long delayMillis = adjustedDelayNanos / 1_000_000L;
    int remainingNanos = (int) (adjustedDelayNanos % 1_000_000L);

    Thread.sleep(delayMillis, remainingNanos);
  }

  private void logPacket(UdpPacketRecord udpPacketRecord) {
    StringBuilder stringBuilder = new StringBuilder();

    stringBuilder
        .append("sent ")
        .append(udpPacketRecord.getPayload().length)
        .append(" bytes to ")
        .append(arguments.getTargetAddress())
        .append(":")
        .append(arguments.getTargetPort())
        .append(new String(udpPacketRecord.getPayload()));

    if (arguments.isHexDump()) {
      stringBuilder
          .append(" ")
          .append(HexFormatHelper.toHex(udpPacketRecord.getPayload()));
    }

    System.out.println(stringBuilder);
  }
}