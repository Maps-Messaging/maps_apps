package io.mapsmessaging.tools.udphelpers.capture;

import lombok.Getter;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Getter
public class UdpCaptureArguments {

  @Option(
      names = "--bind-address",
      defaultValue = "0.0.0.0",
      description = "UDP address to bind to"
  )
  private String bindAddress;

  @Option(
      names = "--port",
      required = true,
      description = "UDP port to capture"
  )
  private int port;

  @Option(
      names = "--output",
      required = true,
      description = "Output capture file"
  )
  private Path outputPath;

  @Option(
      names = "--buffer-size",
      defaultValue = "65535",
      description = "Maximum UDP packet buffer size"
  )
  private int bufferSize;

  @Option(
      names = "--flush-each-packet",
      defaultValue = "true",
      description = "Flush capture file after every packet"
  )
  private boolean flushEachPacket;

  @Option(
      names = "--hex-dump",
      defaultValue = "false",
      description = "Print received packets as hex"
  )
  private boolean hexDump;

  @Option(
      names = "--quiet",
      defaultValue = "false",
      description = "Disable packet logging"
  )
  private boolean quiet;
}