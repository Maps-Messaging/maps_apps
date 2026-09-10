/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import com.google.gson.JsonObject;
import io.mapsmessaging.storage.impl.file.partition.IndexRecord;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dependency-light executable checks, also invoked by the JUnit test. */
public final class StorageInspectorChecks {
  private StorageInspectorChecks() {}

  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("maps-storage-test-");
    try {
      run(root);
      System.out.println("Storage inspector: 16 checks passed");
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  static void run(Path root) throws Exception {
    Path valid = fixture(root.resolve("valid"));
    byte[] before = hash(valid);
    Path data = Path.of(valid + "_data");
    byte[] dataBefore = hash(data);
    List<JsonObject> reports = new ArrayList<>();
    ReadOnlyStore reader = new ReadOnlyStore(1024 * 1024, null, new ReadOnlyStore.Sink() {
      public void report(JsonObject report) { reports.add(report); }
      public void event(JsonObject event) { throw new AssertionError("Structural scan must not export events"); }
    });
    ReadOnlyStore.Stats stats = reader.inspect(valid, null);
    check(stats.errors == 0 && stats.active == 1 && stats.deleted == 1 && stats.unused == 1 && stats.valid == 1, "valid populated store");
    check(Arrays.equals(before, hash(valid)) && Arrays.equals(dataBefore, hash(data)), "source preservation");

    Path metadata = valid.getParent().resolve("resource.yaml");
    Files.writeString(metadata, "!!io.mapsmessaging.engine.resources.ResourceProperties\nresourceName: '/mavlink/1/status'\n");
    check(StoreMetadata.topic(valid.getParent()).equals("/mavlink/1/status"), "server metadata topic");
    Files.writeString(metadata, "!!java.lang.ProcessBuilder {}\n");
    stats = reader.inspect(valid, null);
    check(stats.errors == 0 && stats.warnings == 1, "unsafe metadata rejected without suppressing inspection");
    Files.delete(metadata);

    Path truncated = fixture(root.resolve("truncated"));
    try (var channel = java.nio.channels.FileChannel.open(Path.of(truncated + "_data"), java.nio.file.StandardOpenOption.WRITE)) { channel.truncate(35); }
    check(reader.inspect(truncated, null).errors > 0, "truncated frame");

    Path badIndex = fixture(root.resolve("bad-index"));
    try (var channel = java.nio.channels.FileChannel.open(badIndex, java.nio.file.StandardOpenOption.WRITE)) { channel.truncate(55); }
    check(reader.inspect(badIndex, null).errors > 0, "truncated index");

    Path oversized = fixture(root.resolve("oversized"));
    patch(Path.of(oversized + "_data"), 28, ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array());
    check(reader.inspect(oversized, null).errors > 0, "hostile buffer count");

    Path negative = fixture(root.resolve("negative"));
    patch(Path.of(negative + "_data"), 32, ByteBuffer.allocate(4).putInt(-1).array());
    check(reader.inspect(negative, null).errors > 0, "negative buffer length");

    Path open = fixture(root.resolve("open"));
    patch(open, 0, ByteBuffer.allocate(8).putLong(ReadOnlyStore.OPEN).array());
    stats = reader.inspect(open, null);
    check(stats.errors == 0 && stats.warnings == 1 && stats.indexState.equals("OPEN"), "open state is a warning");

    Path archived = fixture(root.resolve("archived"));
    Files.writeString(Path.of(archived + "_data"), "# Zip file place holder\n");
    stats = reader.inspect(archived, null);
    check(stats.warnings == 1 && stats.active == 1 && stats.valid == 0, "archive is not falsely checked");

    Path empty = fixture(root.resolve("empty"));
    patch(empty, 48, new byte[24]);
    check(reader.inspect(empty, null).active == 0, "empty active index");

    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    PrintStream err = new PrintStream(errors);
    check(StorageInspectorMain.run(new String[]{"--input", valid.getParent().toString()}, err) == 0, "CLI scan");
    check(StorageInspectorMain.run(new String[]{"--input", valid.getParent().toString(), "--report", valid.toString()}, err) == 2
        && Arrays.equals(before, hash(valid)), "output cannot overwrite input");
    Path existing = root.resolve("existing.ndjson");
    Files.writeString(existing, "preserve");
    check(StorageInspectorMain.run(new String[]{"--input", valid.getParent().toString(), "--report", existing.toString()}, err) == 2
        && Files.readString(existing).equals("preserve"), "existing output preserved");
    check(StorageInspectorMain.run(new String[]{"--input", root.toString()}, err) == 1, "recursive corruption returns failure");
    Path orphan = Files.createDirectory(root.resolve("orphan"));
    Files.write(orphan.resolve("partition_0_index_data"), new byte[24]);
    check(StorageInspectorMain.run(new String[]{"--input", orphan.toString()}, err) == 1, "orphan data reported");
  }

  private static Path fixture(Path root) throws Exception {
    Files.createDirectories(root);
    Path index = root.resolve("partition_0_index");
    ByteBuffer buffer = ByteBuffer.allocate(48 + 3 * IndexRecord.HEADER_SIZE);
    buffer.putLong(0).putLong(ReadOnlyStore.MAGIC).putDouble(1.0).putLong(3).putLong(10).putLong(12);
    new IndexRecord(10, 0, 24, 0, 15).update(buffer);
    new IndexRecord(11, 0, 0, 0, 15).update(buffer);
    new IndexRecord(12, 0, 0, 0, 0).update(buffer);
    Files.write(index, buffer.array());
    ByteBuffer data = ByteBuffer.allocate(39);
    data.putLong(0).putLong(ReadOnlyStore.MAGIC).putDouble(1.0);
    data.putInt(7).putInt(1).putInt(3).put(new byte[]{1, 2, 3});
    Files.write(Path.of(index + "_data"), data.array());
    return index;
  }

  private static void patch(Path path, long offset, byte[] bytes) throws Exception {
    try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
      channel.write(ByteBuffer.wrap(bytes), offset);
    }
  }

  private static byte[] hash(Path file) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
  }

  private static void check(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }
}
