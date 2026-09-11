/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.mapsmessaging.storage.StorableFactory;
import io.mapsmessaging.storage.impl.file.partition.IndexRecord;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SelectionChecks {
  private SelectionChecks() {}

  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("maps-selection-");
    try {
      Path selected = fixture(root, new UUID(1, 2), "/wanted");
      Path ignored = fixture(root, new UUID(3, 4), "/other");
      Files.write(ignored, new byte[1]);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      PrintStream err = new PrintStream(output);
      for (int threads : new int[]{1, 8}) {
        output.reset();
        int result = StorageInspectorMain.run(new String[]{"--input", root.toString(), "--destination", "/wanted",
            "--start-event-id", "42", "--end-event-id", "42", "--threads", "" + threads}, err);
        check(result == 0, output.toString());
        check(!output.toString().contains("/other") && !output.toString().contains("INVALID_PHYSICAL_RECORD"), "unselected noise");
        List<JsonObject> rows = output.toString().lines().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        JsonObject summary = rows.get(rows.size() - 1);
        check(summary.get("activeEvents").getAsLong() == 1 && summary.get("partitions").getAsLong() == 1, "filtered totals");
      }
      List<JsonObject> events = new ArrayList<>();
      MessageDecoder decoder = new MessageDecoder(new StorableFactory<MessageExportChecks.FixtureMessage>() {
        public MessageExportChecks.FixtureMessage unpack(ByteBuffer[] buffers) { return new MessageExportChecks.FixtureMessage(buffers[0].array()); }
        public ByteBuffer[] pack(MessageExportChecks.FixtureMessage message) { throw new UnsupportedOperationException(); }
      });
      ReadOnlyStore.Sink sink = new ReadOnlyStore.Sink() {
        public void report(JsonObject report) {}
        public void event(JsonObject event) { events.add(event); }
      };
      var reader = new ReadOnlyStore(1024, 1024, root, decoder, sink, new EventRange(42L, 42L));
      check(reader.inspect(selected, null).decoded == 1 && events.size() == 1 && events.get(0).get("storageKey").getAsLong() == 42, "single event export");
      check(new ReadOnlyStore(1024, 1024, root, null, sink, new EventRange(null, 42L)).inspect(selected, null).active == 1, "end-only bound");
      var broken = new ReadOnlyStore(1024, 1024, root, null, sink, new EventRange(44L, null)).inspect(selected, null);
      check(broken.active == 1 && broken.errors == 1, "start-only selected corruption");
      check(new ReadOnlyStore(1024, 1024, root, null, sink, new EventRange(43L, 43L)).inspect(selected, null).deleted == 1, "deleted ID selection");
      check(new ReadOnlyStore(1024, 1024, root, null, sink, new EventRange(100L, 200L)).inspect(selected, null).skipped, "disjoint partition skipped");
      output.reset();
      check(StorageInspectorMain.run(new String[]{"--input", root.toString(), "--destination", "/absent"}, err) == 3
          && output.toString().contains("NO_MATCHING_DESTINATION") && !output.toString().contains("/other"), "no match diagnostic");
      check(StorageInspectorMain.run(new String[]{"--input", root.toString(), "--start-event-id", "44", "--end-event-id", "42"}, err) == 2, "reversed bounds");
      check(StorageInspectorMain.run(new String[]{"--input", root.toString(), "--end-event-id", "-1"}, err) == 2, "negative ID");
      EventRange maximum = new EventRange(Long.MAX_VALUE, Long.MAX_VALUE);
      check(maximum.firstSlot(Long.MAX_VALUE) == 0 && maximum.lastSlot(Long.MAX_VALUE, 1) == 0, "maximum ID arithmetic");
      System.out.println("Selection checks passed: destination, inclusive/open bounds, export, corruption isolation, deleted IDs, no match and validation");
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static Path fixture(Path root, UUID uuid, String topic) throws Exception {
    Path destination = Files.createDirectories(root.resolve(uuid.toString()));
    Files.writeString(destination.resolve("resource.yaml"), "resourceName: '" + topic + "'\nuuid: '" + uuid.getMostSignificantBits() + ":" + uuid.getLeastSignificantBits() + "'\n");
    Path directory = Files.createDirectories(destination.resolve("message.data"));
    Path index = directory.resolve("partition_0_index");
    ByteBuffer idx = ByteBuffer.allocate(48 + 3 * IndexRecord.HEADER_SIZE);
    idx.putLong(0).putLong(ReadOnlyStore.MAGIC).putDouble(1).putLong(3).putLong(42).putLong(44);
    new IndexRecord(42, 0, 24, 0, 15).update(idx);
    new IndexRecord(43, 0, 0, 0, 15).update(idx);
    new IndexRecord(44, 0, 39, 0, 8).update(idx);
    Files.write(index, idx.array());
    ByteBuffer data = ByteBuffer.allocate(47);
    data.putLong(0).putLong(ReadOnlyStore.MAGIC).putDouble(1).putInt(7).putInt(1).putInt(3).put(new byte[]{1, 2, 3});
    data.putInt(-1).putInt(1); // Corrupt event 44; event 42 remains independently readable.
    Files.write(Path.of(index + "_data"), data.array());
    return index;
  }

  private static void check(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
  }
}
