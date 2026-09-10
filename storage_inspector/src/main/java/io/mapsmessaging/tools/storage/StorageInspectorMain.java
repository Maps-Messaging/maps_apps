/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

public final class StorageInspectorMain {
  private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
  private static final String USAGE = """
      MAPS storage inspector (Java 21)
      Usage: StorageInspectorMain --input DIRECTORY [--output events.ndjson]
             [--report findings.ndjson] [--decode] [--topic TOPIC] [--threads N]
             [--max-record-bytes BYTES] [--max-archive-bytes BYTES]
      Recursively inspects partition_*_index and their _data files using read-only access.
      --output requires the MAPS server and its dependencies on the Java classpath.
      --decode validates messages without requiring an export file.
      --topic overrides resource.yaml for a single store/destination directory.
      --threads defaults to 4; use 1 for serial inspection.
      Outputs must be new files outside the input tree. Reports go to stderr by default.
      Default limits: record 67108864 bytes, expanded local archive 1073741824 bytes.
      Exit: 0 checked, 1 corruption/read failure, 2 usage/runtime error, 3 warnings/incomplete.
      """;

  private StorageInspectorMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.err));
  }

  static int run(String[] args, PrintStream err) {
    try {
      Options options = Options.parse(args);
      if (options.help) {
        err.print(USAGE);
        return 0;
      }
      if (options.decode) new MessageDecoder(); // Fail before opening outputs if the server runtime is missing.
      Path root = options.input.toRealPath();
      if (!Files.isDirectory(root)) throw new IllegalArgumentException("--input must be a directory");
      Path output = outputPath(options.output, root);
      Path report = outputPath(options.report, root);
      if (output != null && output.equals(report)) throw new IllegalArgumentException("--output and --report must differ");
      try (BufferedWriter eventWriter = writer(output); BufferedWriter reportWriter = writer(report)) {
        ReadOnlyStore.Sink sink = new ReadOnlyStore.Sink() {
          @Override public synchronized void report(JsonObject record) throws IOException {
            try {
              if (reportWriter == null) {
                err.println(JSON.toJson(record));
                if (err.checkError()) throw new IOException("Cannot write report to stderr");
              } else write(reportWriter, record);
            } catch (IOException e) { throw new ReadOnlyStore.OutputFailure(e); }
          }
          @Override public synchronized void event(JsonObject event) throws IOException {
            try {
              if (eventWriter != null) write(eventWriter, event);
            } catch (IOException e) { throw new ReadOnlyStore.OutputFailure(e); }
          }
        };
        return scan(root, options, sink);
      }
    } catch (ReflectiveOperationException | LinkageError e) {
      err.println("Cannot load MAPS decoder. Add the matching server JAR and dependency directory to -cp: " + e);
      return 2;
    } catch (IOException | IllegalArgumentException e) {
      err.println("Storage inspection failed: " + e.getMessage());
      return 2;
    }
  }

  private static int scan(Path root, Options options, ReadOnlyStore.Sink sink) throws IOException {
    long[] totals = new long[5];
    Set<Path> directories = new HashSet<>();
    Set<Path> resources = new HashSet<>();
    try (InspectionPool pool = new InspectionPool(options.threads, totals)) {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
          if (Files.exists(directory.resolve("resource.yaml"), LinkOption.NOFOLLOW_LINKS)) {
            resources.add(directory);
            pool.submit(() -> inspectResource(directory, sink));
          }
          return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          String name = file.getFileName().toString();
          if (name.startsWith("partition_")) {
            if (name.matches("partition_[0-9]+_index")) directories.add(file.getParent());
            pool.submit(() -> inspectFile(file, root, options, sink));
          }
          return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
          issue(file, "ERROR", "DISCOVERY_FAILED", exception.toString(), sink);
          totals[1]++;
          return FileVisitResult.CONTINUE;
        }
      });
      pool.finish();
    }
    if (totals[0] == 0) {
      issue(root, "WARNING", "NO_STORES", "No supported partition indexes discovered", sink);
      totals[2]++;
    }
    JsonObject summary = ReadOnlyStore.base(root, "summary");
    summary.addProperty("threads", options.threads);
    summary.addProperty("stores", directories.size());
    summary.addProperty("resources", resources.size());
    summary.addProperty("partitions", totals[0]);
    summary.addProperty("errors", totals[1]);
    summary.addProperty("warnings", totals[2]);
    summary.addProperty("activeEvents", totals[3]);
    summary.addProperty("decodedEvents", totals[4]);
    sink.report(summary);
    return totals[1] > 0 ? 1 : totals[2] > 0 ? 3 : 0;
  }

  private static long[] inspectResource(Path directory, ReadOnlyStore.Sink sink) throws IOException {
    long[] totals = new long[5];
    if (Files.exists(directory.resolve("resource.yaml"), LinkOption.NOFOLLOW_LINKS)) {
      Path store = directory.resolve("message.data");
      JsonObject resource = ReadOnlyStore.base(directory, "resource");
      resource.addProperty("storeDirectory", store.toString());
      resource.addProperty("storageBackend", "UNKNOWN_REQUIRES_SERVER_CONFIGURATION");
      try {
        StoreMetadata metadata = StoreMetadata.load(directory);
        resource.add("properties", metadata.json());
        JsonObject values = metadata.json();
        if (!values.get("serverIdentityValid").getAsBoolean()) {
          issue(directory, "WARNING", "INVALID_RESOURCE_IDENTITY", "Server cannot reload the UUID pair; physical partitions will still be inspected", sink);
          totals[2]++;
        } else if (!directory.getFileName().toString().equals(values.get("normalizedUuid").getAsString())) {
          issue(directory, "WARNING", "UUID_DIRECTORY_MISMATCH", "Directory differs from resource UUID; inspect files here without redirecting to another path", sink);
          totals[2]++;
        }
      } catch (IOException e) {
        if (e instanceof ReadOnlyStore.OutputFailure) throw e;
        issue(directory, "ERROR", "METADATA_UNREADABLE", e.toString(), sink);
        totals[1]++;
      }
      if (!Files.isDirectory(store, LinkOption.NOFOLLOW_LINKS)) {
        resource.addProperty("dataState", "NO_LOCAL_MESSAGE_STORE");
        issue(directory, "WARNING", "NO_LOCAL_MESSAGE_STORE", "resource.yaml exists but message.data is absent or not a directory; metadata alone cannot distinguish memory-only from missing storage", sink);
        totals[2]++;
      } else {
        boolean partitions;
        try (var entries = Files.newDirectoryStream(store, "partition_*_index")) {
          partitions = entries.iterator().hasNext();
        } catch (IOException e) {
          issue(store, "ERROR", "DISCOVERY_FAILED", e.toString(), sink);
          totals[1]++;
          resource.addProperty("dataState", "UNREADABLE");
          sink.report(resource);
          return totals;
        }
        resource.addProperty("dataState", partitions ? "LOCAL_PARTITIONS" : "NO_PARTITIONS");
        if (!partitions) {
          issue(store, "WARNING", "NO_PARTITIONS", "No local partition indexes; storage configuration is required to interpret this destination", sink);
          totals[2]++;
        }
      }
      sink.report(resource);
    }
    return totals;
  }

  private static long[] inspectFile(Path file, Path root, Options options, ReadOnlyStore.Sink sink) throws Exception {
    long[] totals = new long[5];
    String name = file.getFileName().toString();
    if (name.matches("partition_[0-9]+_index")) {
      if (options.topic != null && !file.getParent().equals(root) && !file.getParent().equals(root.resolve("message.data"))) {
        throw new IllegalArgumentException("--topic requires a single store directory without nested stores");
      }
      MessageDecoder decoder = options.decode ? new MessageDecoder() : null;
      ReadOnlyStore reader = new ReadOnlyStore(options.maxRecordBytes, options.maxArchiveBytes, root, decoder, sink);
      ReadOnlyStore.Stats result = reader.inspect(file, options.topic);
      totals[0]++;
      totals[1] += result.errors;
      totals[2] += result.warnings;
      totals[3] += result.active;
      totals[4] += result.decoded;
    } else if (name.matches("partition_[0-9]+_index_data")) {
      Path index = file.resolveSibling(name.substring(0, name.length() - 5));
      if (!Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
        issue(file, "ERROR", "ORPHAN_DATA", "Data file has no index; events cannot be classified as active", sink);
        totals[1]++;
      }
    } else if (name.matches("partition_[0-9]+_index_data_zip")) {
      Path placeholder = file.resolveSibling(name.substring(0, name.length() - 4));
      Path index = file.resolveSibling(name.substring(0, name.length() - 9));
      if (!Files.exists(placeholder, LinkOption.NOFOLLOW_LINKS) || !Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
        issue(file, "WARNING", "ORPHAN_ARCHIVE", "Archive has no matching index/placeholder", sink);
        totals[2]++;
      } else {
        // A sidecar beside live data may be a leftover archive and has not been inspected.
        try (var data = ReadOnlyStore.open(placeholder)) {
          if (data.size() == 0 || ReadOnlyStore.read(data, 0, 1).get() != '#') {
            issue(file, "WARNING", "UNINSPECTED_ARCHIVE", "Archive sidecar exists beside non-archived data", sink);
            totals[2]++;
          }
        } catch (IOException e) {
          if (e instanceof ReadOnlyStore.OutputFailure) throw e;
          issue(file, "ERROR", "DISCOVERY_FAILED", e.toString(), sink);
          totals[1]++;
        }
      }
    } else if (name.startsWith("partition_")) {
      issue(file, "WARNING", "UNINSPECTED_FILE", "Archive, temporary or unsupported partition file", sink);
      totals[2]++;
    }
    return totals;
  }

  private static void issue(Path file, String severity, String code, String detail, ReadOnlyStore.Sink sink) throws IOException {
    JsonObject issue = ReadOnlyStore.base(file, "finding");
    issue.addProperty("severity", severity);
    issue.addProperty("code", code);
    issue.addProperty("detail", detail);
    sink.report(issue);
  }

  private static Path outputPath(Path value, Path root) throws IOException {
    if (value == null) return null;
    Path absolute = value.toAbsolutePath().normalize();
    Path resolved = absolute.getParent().toRealPath().resolve(absolute.getFileName());
    if (resolved.startsWith(root)) throw new IllegalArgumentException("Output must be outside the input tree: " + resolved);
    if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Refusing to overwrite " + resolved);
    return resolved;
  }

  private static BufferedWriter writer(Path path) throws IOException {
    return path == null ? null : Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  private static void write(BufferedWriter writer, JsonObject record) throws IOException {
    writer.write(JSON.toJson(record));
    writer.newLine();
  }

  private static final class Options {
    Path input;
    Path output;
    Path report;
    int threads = 4;
    boolean decode;
    boolean help;
    String topic;
    long maxRecordBytes = 64L * 1024 * 1024;
    long maxArchiveBytes = 1024L * 1024 * 1024;

    static Options parse(String[] args) {
      Options options = new Options();
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < args.length; i++) {
        String arg = args[i];
        if (!seen.add(arg)) throw new IllegalArgumentException("Repeated option: " + arg);
        if (arg.equals("--help") || arg.equals("-h")) { options.help = true; continue; }
        if (arg.equals("--decode")) { options.decode = true; continue; }
        if (++i >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
        String value = args[i];
        switch (arg) {
          case "--threads" -> options.threads = Integer.parseInt(value);
          case "--input" -> options.input = Path.of(value);
          case "--output" -> { options.output = Path.of(value); options.decode = true; }
          case "--report" -> options.report = Path.of(value);
          case "--topic" -> options.topic = value;
          case "--max-record-bytes" -> options.maxRecordBytes = Long.parseLong(value);
          case "--max-archive-bytes" -> options.maxArchiveBytes = Long.parseLong(value);
          default -> throw new IllegalArgumentException("Unknown option: " + arg);
        }
      }
      if (!options.help && options.input == null) throw new IllegalArgumentException("--input is required; use --help");
      if (options.maxRecordBytes < 12 || options.maxRecordBytes > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("--max-record-bytes must be between 12 and 2147483647");
      }
      if (options.maxArchiveBytes < 24) throw new IllegalArgumentException("--max-archive-bytes must be at least 24");
      if (options.threads < 1 || options.threads > 256) throw new IllegalArgumentException("--threads must be between 1 and 256");
      return options;
    }
  }
}
