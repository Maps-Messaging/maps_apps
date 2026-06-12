package io.mapsmessaging.tools.udphelpers.convert;

import lombok.Getter;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Getter
public class UdpStreamConvertArguments {

  @Option(
      names = "--input",
      required = true,
      description = "Input raw stream file"
  )
  private Path inputPath;

  @Option(
      names = "--output",
      required = true,
      description = "Output UDP helper capture file"
  )
  private Path outputPath;

  @Option(
      names = "--source-address",
      defaultValue = "127.0.0.1",
      description = "Source address to store in generated packet records"
  )
  private String sourceAddress;

  @Option(
      names = "--source-port",
      defaultValue = "0",
      description = "Source port to store in generated packet records"
  )
  private int sourcePort;

  @Option(
      names = "--flush-each-packet",
      defaultValue = "true",
      description = "Flush output file after every generated packet"
  )
  private boolean flushEachPacket;

  @Option(
      names = "--quiet",
      defaultValue = "false",
      description = "Disable packet logging"
  )
  private boolean quiet;
}