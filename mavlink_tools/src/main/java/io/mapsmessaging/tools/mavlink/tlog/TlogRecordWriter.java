package io.mapsmessaging.tools.mavlink.tlog;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class TlogRecordWriter implements Closeable {

  private final DataOutputStream outputStream;

  public TlogRecordWriter(Path outputPath) throws IOException {
    Path absoluteOutputPath = outputPath.toAbsolutePath();
    Path parentPath = absoluteOutputPath.getParent();
    if (parentPath != null) {
      Files.createDirectories(parentPath);
    }

    this.outputStream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
        absoluteOutputPath,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
    )));
  }

  public void write(TlogRecord tlogRecord) throws IOException {
    outputStream.writeLong(tlogRecord.timestampMicros());
    outputStream.write(tlogRecord.packetData());
  }

  @Override
  public void close() throws IOException {
    outputStream.close();
  }
}
