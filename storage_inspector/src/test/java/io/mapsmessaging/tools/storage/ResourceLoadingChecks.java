/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import com.google.gson.JsonObject;
import io.mapsmessaging.storage.StorableFactory;
import io.mapsmessaging.storage.impl.file.partition.IndexRecord;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

public final class ResourceLoadingChecks {
  private ResourceLoadingChecks() {}

  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("maps-resource-test-");
    try {
      run(root);
      System.out.println("Resource loading: 12 checks passed");
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static void run(Path root) throws Exception {
    UUID uuid = new UUID(-100, 200);
    Path destination = Files.createDirectories(root.resolve(uuid.toString()));
    Path store = Files.createDirectories(destination.resolve("message.data"));
    Path metadata = destination.resolve("resource.yaml");
    Files.writeString(metadata, """
        !!io.mapsmessaging.engine.resources.ResourceProperties
        resourceName: /mavlink/1/status
        uuid: '-100:200'
        type: TOPIC
        date: 2026-09-10T12:30:00Z
        buildDate: '2026-09-10'
        buildVersion: 4.5.0-SNAPSHOT
        schemaId: mavlink-status
        schema:
          fields: [heading, latitude, longitude]
        """);
    StoreMetadata properties = StoreMetadata.load(store);
    check(properties.topic().equals("/mavlink/1/status") && properties.json().get("normalizedUuid").getAsString().equals(uuid.toString())
        && properties.json().get("schema").getAsJsonObject().has("fields"), "complete parent ResourceProperties");

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream errors = new PrintStream(output);
    check(StorageInspectorMain.run(new String[]{"--input", destination.toString()}, errors) == 3
        && output.toString().contains("NO_PARTITIONS"), "empty metadata destination is discoverable");
    Path index = fixture(store);
    output.reset();
    check(StorageInspectorMain.run(new String[]{"--input", root.toString()}, errors) == 0
        && output.toString().contains("LOCAL_PARTITIONS") && output.toString().contains("4.5.0-SNAPSHOT"), "resource-led scan succeeds");

    List<JsonObject> events = new ArrayList<>();
    List<JsonObject> reports = new ArrayList<>();
    MessageDecoder decoder = new MessageDecoder(new StorableFactory<MessageExportChecks.FixtureMessage>() {
      public MessageExportChecks.FixtureMessage unpack(ByteBuffer[] buffers) { return new MessageExportChecks.FixtureMessage(buffers[0].array()); }
      public ByteBuffer[] pack(MessageExportChecks.FixtureMessage message) { throw new UnsupportedOperationException(); }
    });
    ReadOnlyStore reader = new ReadOnlyStore(1024 * 1024, decoder, new ReadOnlyStore.Sink() {
      public void report(JsonObject value) { reports.add(value); }
      public void event(JsonObject value) { events.add(value); }
    });
    check(reader.inspect(index, null).decoded == 1 && events.get(0).get("topic").getAsString().equals("/mavlink/1/status")
        && events.get(0).get("storageResource").getAsJsonObject().get("type").getAsString().equals("TOPIC"), "metadata carried into streamed event");

    Path data = Path.of(index + "_data");
    byte[] bytes = Files.readAllBytes(data);
    Path zip = Path.of(data + "_zip");
    try (var stream = new GZIPOutputStream(Files.newOutputStream(zip))) { stream.write(bytes); }
    String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    String placeholder = "# Zip file place holder\nSHA-256\n" + hash + "\n" + bytes.length + "\n2026-09-10T12:30:00\n";
    Files.writeString(data, placeholder);
    byte[] zipBefore = Files.readAllBytes(zip);
    byte[] indexBefore = Files.readAllBytes(index);
    events.clear();
    var stats = reader.inspect(index, null);
    check(stats.errors == 0 && stats.warnings == 0 && stats.decoded == 1 && stats.dataState.equals("COMPRESSED_CLOSED"), "compressed partition loads");
    check(Arrays.equals(zipBefore, Files.readAllBytes(zip)) && Arrays.equals(indexBefore, Files.readAllBytes(index))
        && Files.readString(data).equals(placeholder), "compressed source preservation");
    output.reset();
    check(StorageInspectorMain.run(new String[]{"--input", root.toString()}, errors) == 0, "archive sidecar counted as inspected");
    check(reports.stream().anyMatch(report -> report.has("digestVerified") && report.get("digestVerified").getAsBoolean()), "digest verified");
    Files.writeString(data, placeholder.replace(hash, "bad-digest"));
    events.clear();
    stats = reader.inspect(index, null);
    check(stats.errors > 0 && stats.active == 1 && events.isEmpty(), "bad archive digest does not export messages");
    Files.writeString(data, placeholder);
    output.reset();
    check(StorageInspectorMain.run(new String[]{"--input", root.toString(), "--max-archive-bytes", "24"}, errors) == 3
        && output.toString().contains("ARCHIVE_NOT_INSPECTED"), "archive expansion limit");
    Files.write(zip, Arrays.copyOf(zipBefore, zipBefore.length - 5));
    check(reader.inspect(index, null).errors > 0, "truncated gzip rejected");
    Files.write(zip, zipBefore);
    Files.delete(zip);
    check(reader.inspect(index, null).errors > 0, "missing gzip rejected");
  }

  private static Path fixture(Path directory) throws Exception {
    Path index = directory.resolve("partition_0_index");
    ByteBuffer buffer = ByteBuffer.allocate(48 + IndexRecord.HEADER_SIZE);
    buffer.putLong(0).putLong(ReadOnlyStore.MAGIC).putDouble(1.0).putLong(1).putLong(42).putLong(42);
    new IndexRecord(42, 0, 24, 0, 15).update(buffer);
    Files.write(index, buffer.array());
    ByteBuffer data = ByteBuffer.allocate(39);
    data.putLong(0).putLong(ReadOnlyStore.MAGIC).putDouble(1.0).putInt(7).putInt(1).putInt(3).put(new byte[]{0, (byte) 255, 1});
    Files.write(Path.of(index + "_data"), data.array());
    return index;
  }

  private static void check(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }
}
