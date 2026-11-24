package io.mapsmessaging.tools.mqttreplay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public final class NdjsonReplayReader implements AutoCloseable {
  private final List<Source> sources = new ArrayList<>();
  private final PriorityQueue<Source> queue = new PriorityQueue<>(
      Comparator.comparing((Source source) -> source.current.timestamp())
          .thenComparingInt(source -> source.index));

  NdjsonReplayReader(Path directory, int qos) throws IOException {
    try {
      List<Path> files;
      try (Stream<Path> stream = Files.walk(directory)) {
        files = stream.filter(Files::isRegularFile)
            .filter(NdjsonReplayReader::supported)
            .sorted()
            .toList();
      }
      if (files.isEmpty()) {
        throw new IOException("No .ndjson or .ndjson.gz files in " + directory);
      }
      for (Path file : files) {
        Source source = new Source(file, sources.size(), qos);
        sources.add(source);
        if (source.advance()) {
          queue.add(source);
        }
      }
    } catch (IOException | RuntimeException exception) {
      try {
        close();
      } catch (IOException cleanup) {
        exception.addSuppressed(cleanup);
      }
      throw exception;
    }
  }

  private static boolean supported(Path path) {
    String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return filename.endsWith(".ndjson") || filename.endsWith(".ndjson.gz");
  }

  ReplayEvent next() throws IOException {
    Source source = queue.poll();
    if (source == null) {
      return null;
    }
    ReplayEvent event = source.current;
    if (source.advance()) {
      if (source.current.timestamp().isBefore(event.timestamp())) {
        throw new IOException("Input file is not timestamp ordered: " + source.path);
      }
      queue.add(source);
    }
    return event;
  }

  @Override
  public void close() throws IOException {
    IOException error = null;
    for (Source source : sources) {
      try {
        source.reader.close();
      } catch (IOException exception) {
        if (error == null) {
          error = exception;
        } else {
          error.addSuppressed(exception);
        }
      }
    }
    if (error != null) {
      throw error;
    }
  }

  private static final class Source {
    private final Path path;
    private final int index;
    private final int qos;
    private final BufferedReader reader;
    private long lineNumber;
    private ReplayEvent current;

    Source(Path path, int index, int qos) throws IOException {
      this.path = path;
      this.index = index;
      this.qos = qos;
      this.reader = path.toString().toLowerCase(Locale.ROOT).endsWith(".gz")
          ? new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))
          : Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }

    boolean advance() throws IOException {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        try {
          current = NdjsonReplayDecoder.decode(line, qos);
        } catch (RuntimeException exception) {
          throw new IOException("Invalid event " + path + ":" + lineNumber, exception);
        }
        return true;
      }
      current = null;
      return false;
    }
  }
}
