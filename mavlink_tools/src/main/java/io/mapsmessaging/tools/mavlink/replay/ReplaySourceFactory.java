package io.mapsmessaging.tools.mavlink.replay;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class ReplaySourceFactory {

  private static final byte[] MUDP_MAGIC = "MUDP".getBytes(StandardCharsets.US_ASCII);

  public ReplaySource create(Path captureFile, ReplayFormat replayFormat, long rawDelayNanos) throws IOException {
    ReplayFormat resolvedFormat = replayFormat == ReplayFormat.AUTO ? detect(captureFile) : replayFormat;
    return switch (resolvedFormat) {
      case TLOG -> new TlogReplaySource(captureFile);
      case MUDP -> new MudpReplaySource(captureFile);
      case LEGACY_DAT -> new LegacyDatReplaySource(captureFile);
      case RAW -> new RawMavlinkReplaySource(captureFile, rawDelayNanos);
      case AUTO -> throw new IOException("Unable to resolve replay format for " + captureFile);
    };
  }

  public ReplayFormat detect(Path captureFile) throws IOException {
    try (InputStream inputStream = Files.newInputStream(captureFile)) {
      byte[] header = inputStream.readNBytes(MUDP_MAGIC.length);
      if (java.util.Arrays.equals(header, MUDP_MAGIC)) {
        return ReplayFormat.MUDP;
      }
    }

    String fileName = captureFile.getFileName().toString().toLowerCase(Locale.ROOT);
    if (fileName.endsWith(".tlog")) {
      return ReplayFormat.TLOG;
    }
    if (fileName.endsWith(".mudp") || fileName.endsWith(".udp")) {
      return ReplayFormat.MUDP;
    }
    if (fileName.endsWith(".dat") || fileName.endsWith(".csv")) {
      return ReplayFormat.LEGACY_DAT;
    }
    if (fileName.endsWith(".raw") || fileName.endsWith(".rlog") || fileName.endsWith(".bin")) {
      return ReplayFormat.RAW;
    }
    throw new IOException("Unable to detect capture format for " + captureFile + "; use --format");
  }
}
