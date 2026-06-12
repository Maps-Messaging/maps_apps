package io.mapsmessaging.tools.udphelpers;

import io.mapsmessaging.tools.udphelpers.replay.UdpPacketRecordReader;
import io.mapsmessaging.tools.udphelpers.replay.UdpPacketReplay;
import io.mapsmessaging.tools.udphelpers.replay.UdpReplayArguments;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.Callable;

@Command(
    name = "udp-replay",
    mixinStandardHelpOptions = true,
    description = "Replay captured UDP packets to a target address and port"
)
public class UdpReplayMain implements Callable<Integer> {

  @Mixin
  private UdpReplayArguments arguments;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new UdpReplayMain()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    do {
      try (
          InputStream inputStream = Files.newInputStream(arguments.getInputPath());
          UdpPacketRecordReader udpPacketRecordReader = new UdpPacketRecordReader(inputStream)
      ) {
        UdpPacketReplay udpPacketReplay = new UdpPacketReplay(arguments);
        udpPacketReplay.run(udpPacketRecordReader);
      }
    } while (arguments.isLoop());

    return 0;
  }
}