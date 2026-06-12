package io.mapsmessaging.tools.udphelpers.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.net.InetAddress;

@Getter
@RequiredArgsConstructor
public class UdpPacketRecord {

  private final long timestampNanos;
  private final InetAddress sourceAddress;
  private final int sourcePort;
  private final byte[] payload;
}