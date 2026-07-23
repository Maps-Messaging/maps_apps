package io.mapsmessaging.tools.mavlink.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class LegacyDatReplaySource implements ReplaySource {

  private final BufferedReader bufferedReader;
  private long packetNumber;
  private long lineNumber;

  public LegacyDatReplaySource(Path captureFile) throws IOException {
    this.bufferedReader = Files.newBufferedReader(captureFile, StandardCharsets.UTF_8);
  }

  @Override
  public ReplayFrame nextFrame() throws IOException {
    String line;
    while ((line = bufferedReader.readLine()) != null) {
      lineNumber++;
      if (line.isBlank()) {
        continue;
      }

      int separatorIndex = line.indexOf(',');
      if (separatorIndex <= 0 || separatorIndex == line.length() - 1) {
        throw new IOException("Invalid legacy capture record at line " + lineNumber);
      }

      try {
        long delayMilliseconds = Long.parseLong(line.substring(0, separatorIndex).trim());
        byte[] packetData = Base64.getDecoder().decode(line.substring(separatorIndex + 1).trim());
        return new ReplayFrame(
            ++packetNumber,
            "legacy-dat",
            Math.multiplyExact(Math.max(0, delayMilliseconds), 1_000_000L),
            -1,
            packetData
        );
      } catch (ArithmeticException | IllegalArgumentException exception) {
        throw new IOException("Invalid legacy capture record at line " + lineNumber, exception);
      }
    }
    return null;
  }

  @Override
  public void close() throws IOException {
    bufferedReader.close();
  }
}
