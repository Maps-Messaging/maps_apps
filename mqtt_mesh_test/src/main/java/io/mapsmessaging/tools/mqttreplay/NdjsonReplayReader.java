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
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public final class NdjsonReplayReader implements AutoCloseable {
  private final List<Source> sources = new ArrayList<>();
  private LargestPacket largestPacket;
  private final Map<String, Long> topicCounts = new HashMap<>();

  Map<String, Long> topicCounts() {
    return Map.copyOf(topicCounts);
  }
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
      List<ScanResult> scans = scanFiles(files, qos);
      for (ScanResult scan : scans) {
        scan.topicCounts().forEach((topic, count) -> topicCounts.merge(topic, count, Long::sum));
        if (scan.largestPacket() != null && (largestPacket == null || scan.largestPacket().packetBytes() > largestPacket.packetBytes())) {
          largestPacket = scan.largestPacket();
        }
      }
      if (largestPacket != null) {
        System.out.printf("Largest MQTT 3.1.1 PUBLISH packet: %,d bytes (payload %,d bytes, QoS %d), topic: %s, file: %s:%d%n",
            largestPacket.packetBytes(), largestPacket.payloadBytes(), largestPacket.qos(),
            largestPacket.topic(), largestPacket.path(), largestPacket.lineNumber());
      }
      for (int i = 0; i < files.size(); i++) {
        Path file = files.get(i);
        Source source = new Source(file, sources.size(), qos, scans.get(i).totalRecords());
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

  private static List<ScanResult> scanFiles(List<Path> files, int qos) throws IOException {
    int workers = Math.min(files.size(), Math.min(Runtime.getRuntime().availableProcessors(), 8));
    ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, workers));
    List<Future<ScanResult>> futures = new ArrayList<>(files.size());
    try {
      for (Path file : files) {
        futures.add(executor.submit(() -> Source.scan(file, qos)));
      }
      List<ScanResult> scans = new ArrayList<>(files.size());
      for (Future<ScanResult> future : futures) {
        try {
          scans.add(future.get());
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while counting replay records", exception);
        } catch (ExecutionException exception) {
          Throwable cause = exception.getCause();
          if (cause instanceof IOException ioException) {
            throw ioException;
          }
          throw new IOException("Unable to count replay records", cause);
        }
      }
      return scans;
    } finally {
      for (Future<ScanResult> future : futures) {
        future.cancel(true);
      }
      executor.shutdownNow();
    }
  }

  static long packetBytes(ReplayEvent event) {
    long remaining = 2L + event.topic().getBytes(StandardCharsets.UTF_8).length
        + (event.qos() == 0 ? 0 : 2) + event.payload().length;
    int lengthBytes = remaining < 128 ? 1 : remaining < 16384 ? 2 : remaining < 2097152 ? 3 : 4;
    return 1L + lengthBytes + remaining;
  }

  record LargestPacket(long packetBytes, int payloadBytes, int qos, String topic, Path path, long lineNumber) {
  }

  private record ScanResult(long totalRecords, LargestPacket largestPacket, Map<String, Long> topicCounts) {
  }

  LargestPacket largestPacket() {
    return largestPacket;
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

    Source(Path path, int index, int qos, long totalRecords) throws IOException {
      this.path = path;
      this.index = index;
      this.qos = qos;
      this.totalRecords = totalRecords;
      this.reader = open(path);
    }

    private static ScanResult scan(Path path, int qos) throws IOException {
      try (BufferedReader counter = open(path)) {
        long count = 0;
        long lineNumber = 0;
        LargestPacket largest = null;
        Map<String, Long> counts = new HashMap<>();
        String line;
        while ((line = counter.readLine()) != null) {
          lineNumber++;
          if (!line.isBlank()) {
            count++;
            try {
              ReplayEvent event = NdjsonReplayDecoder.decode(line, qos);
              counts.merge(event.topic(), 1L, Long::sum);
              long bytes = packetBytes(event);
              if (largest == null || bytes > largest.packetBytes()) {
                largest = new LargestPacket(bytes, event.payload().length, event.qos(), event.topic(), path, lineNumber);
              }
            } catch (RuntimeException ignored) {
              // Match the replay path: invalid records are counted but skipped when publishing.
            }
          }
          if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Interrupted while scanning " + path);
          }
        }
        return new ScanResult(count, largest, counts);
      }
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
