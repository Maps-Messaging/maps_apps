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
        System.out.printf("Opening replay log %d/%d: %s (%,d records)%n",
            source.index + 1, files.size(), file, source.totalRecords);
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

  long totalRecords() {
    return sources.stream().mapToLong(source -> source.totalRecords).sum();
  }

  long recordsRead() {
    return sources.stream().mapToLong(source -> source.recordsRead).sum();
  }

  long skippedRecords() {
    return sources.stream().mapToLong(source -> source.skippedRecords).sum();
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
    private final long totalRecords;
    private long lineNumber;
    private long recordsRead;
    private long skippedRecords;
    private ReplayEvent current;

    Source(Path path, int index, int qos) throws IOException {
      this.path = path;
      this.index = index;
      this.qos = qos;
      try (BufferedReader counter = open(path)) {
        long count = 0;
        String line;
        while ((line = counter.readLine()) != null) {
          if (!line.isBlank()) {
            count++;
          }
        }
        totalRecords = count;
      }
      this.reader = open(path);
    }

    private static BufferedReader open(Path path) throws IOException {
      return path.toString().toLowerCase(Locale.ROOT).endsWith(".gz")
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
        recordsRead++;
        try {
          current = NdjsonReplayDecoder.decode(line, qos);
        } catch (RuntimeException exception) {
          skippedRecords++;
          String reason = exception.getMessage();
          if (reason == null || reason.isBlank()) {
            reason = exception.getClass().getSimpleName();
          }
          reason = reason.replace('\n', ' ').replace('\r', ' ');
          if (reason.length() > 200) {
            reason = reason.substring(0, 200) + "...";
          }
          System.err.println("Skipping invalid event " + path + ":" + lineNumber + ": " + reason);
          continue;
        }
        return true;
      }
      current = null;
      return false;
    }
  }
}
