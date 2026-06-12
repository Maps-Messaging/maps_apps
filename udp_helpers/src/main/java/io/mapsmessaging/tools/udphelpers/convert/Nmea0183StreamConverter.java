package io.mapsmessaging.tools.udphelpers.convert;

import io.mapsmessaging.tools.udphelpers.capture.UdpPacketRecordWriter;
import io.mapsmessaging.tools.udphelpers.common.UdpPacketRecord;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class Nmea0183StreamConverter {

  private final UdpStreamConvertArguments arguments;

  public Nmea0183StreamConverter(UdpStreamConvertArguments arguments) {
    this.arguments = arguments;
  }

  public void convert(
      InputStream inputStream,
      UdpPacketRecordWriter udpPacketRecordWriter
  ) throws IOException {
    InetAddress sourceAddress = InetAddress.getByName(arguments.getSourceAddress());

    boolean insideRecord = false;
    StringBuilder recordBuilder = new StringBuilder();

    while (true) {
      int value = inputStream.read();

      if (value < 0) {
        writeRecordIfRequired(sourceAddress, recordBuilder, udpPacketRecordWriter);
        break;
      }

      char character = (char) value;

      if (character == '$') {
        if (insideRecord) {
          writeRecordIfRequired(sourceAddress, recordBuilder, udpPacketRecordWriter);
          recordBuilder.setLength(0);
        }

        insideRecord = true;
        recordBuilder.append(character);
      } else if (insideRecord) {
        recordBuilder.append(character);
      }
    }
  }

  private void writeRecordIfRequired(
      InetAddress sourceAddress,
      StringBuilder recordBuilder,
      UdpPacketRecordWriter udpPacketRecordWriter
  ) throws IOException {
    String record = recordBuilder.toString().trim();

    if (record.isEmpty()) {
      return;
    }

    byte[] payload = record.getBytes(StandardCharsets.US_ASCII);

    UdpPacketRecord udpPacketRecord = new UdpPacketRecord(
        System.nanoTime(),
        sourceAddress,
        arguments.getSourcePort(),
        payload
    );

    udpPacketRecordWriter.write(udpPacketRecord);

    if (!arguments.isQuiet()) {
      System.out.println("converted " + payload.length + " bytes: " + record);
    }
  }
}