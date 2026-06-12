package io.mapsmessaging.tools.udphelpers.replay;

import lombok.Getter;
import picocli.CommandLine.Option;

import java.nio.file.Path;

@Getter
public class UdpReplayArguments {

  @Option(
      names = "--input",
      required = true,
      description = "Input capture file"
  )
  private Path inputPath;

  @Option(
      names = "--target-address",
      required = true,
      description = "Target UDP address"
  )
  private String targetAddress;

  @Option(
      names = "--target-port",
      required = true,
      description = "Target UDP port"
  )
  private int targetPort;

  @Option(
      names = "--preserve-timing",
      defaultValue = "true",
      description = "Replay packets using captured packet spacing"
  )
  private boolean preserveTiming;

  @Option(
      names = "--speed",
      defaultValue = "1.0",
      description = "Replay speed multiplier"
  )
  private double speed;

  @Option(
      names = "--loop",
      defaultValue = "false",
      description = "Loop replay forever"
  )
  private boolean loop;

  @Option(
      names = "--hex-dump",
      defaultValue = "false",
      description = "Print sent packets as hex"
  )
  private boolean hexDump;

  @Option(
      names = "--quiet",
      defaultValue = "false",
      description = "Disable packet logging"
  )
  private boolean quiet;
}