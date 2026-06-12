package io.mapsmessaging.tools.udphelpers;

import io.mapsmessaging.tools.udphelpers.capture.UdpPacketRecordWriter;
import io.mapsmessaging.tools.udphelpers.convert.Nmea0183StreamConverter;
import io.mapsmessaging.tools.udphelpers.convert.UdpStreamConvertArguments;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;

@Command(
    name = "udp-stream-convert",
    mixinStandardHelpOptions = true,
    description = "Convert a raw stream file into a UDP helper capture file"
)
public class UdpStreamConvertMain implements Callable<Integer> {

  @Mixin
  private UdpStreamConvertArguments arguments;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new UdpStreamConvertMain()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    StandardOpenOption[] standardOpenOptions = new StandardOpenOption[]{
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING
    };
    Path file = arguments.getInputPath();
    System.out.println("Input file: " + file.toAbsolutePath());
    try (
        InputStream inputStream = Files.newInputStream(arguments.getInputPath());
        OutputStream outputStream = Files.newOutputStream(arguments.getOutputPath(), standardOpenOptions);
        UdpPacketRecordWriter udpPacketRecordWriter = new UdpPacketRecordWriter(
            outputStream,
            arguments.isFlushEachPacket()
        )
    ) {
      Nmea0183StreamConverter converter = new Nmea0183StreamConverter(arguments);
      converter.convert(inputStream, udpPacketRecordWriter);
    }

    return 0;
  }
}