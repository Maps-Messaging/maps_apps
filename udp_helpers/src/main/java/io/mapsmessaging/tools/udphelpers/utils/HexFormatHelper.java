package io.mapsmessaging.tools.udphelpers.utils;

public class HexFormatHelper {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private HexFormatHelper() {
  }

  public static String toHex(byte[] payload) {
    char[] output = new char[payload.length * 2];

    for (int index = 0; index < payload.length; index++) {
      int value = payload[index] & 0xff;

      output[index * 2] = HEX[value >>> 4];
      output[index * 2 + 1] = HEX[value & 0x0f];
    }

    return new String(output);
  }
}