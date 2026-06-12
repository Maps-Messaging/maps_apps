package io.mapsmessaging.tools.udphelpers;

import io.mapsmessaging.tools.udphelpers.capture.UdpCaptureArguments;
import io.mapsmessaging.tools.udphelpers.capture.UdpPacketCapture;
import io.mapsmessaging.tools.udphelpers.capture.UdpPacketRecordWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;

@Command(
    name = "udp-capture",
    mixinStandardHelpOptions = true,
    description = "Capture UDP packets to a replayable file"
)
public class UdpCaptureMain implements Callable<Integer> {

  @Mixin
  private UdpCaptureArguments arguments;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new UdpCaptureMain()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    StandardOpenOption[] standardOpenOptions = new StandardOpenOption[]{
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING
    };

    try (
        OutputStream outputStream = Files.newOutputStream(arguments.getOutputPath(), standardOpenOptions);
        UdpPacketRecordWriter udpPacketRecordWriter = new UdpPacketRecordWriter(
            outputStream,
            arguments.isFlushEachPacket()
        )
    ) {
      UdpPacketCapture udpPacketCapture = new UdpPacketCapture(arguments);

      Runtime.getRuntime().addShutdownHook(new Thread(udpPacketCapture::stop));

      udpPacketCapture.run(udpPacketRecordWriter);
    }

    return 0;
  }
}