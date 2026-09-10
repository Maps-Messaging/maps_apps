/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import com.google.gson.JsonObject;
import io.mapsmessaging.storage.impl.file.partition.IndexRecord;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Version 1 partition reader. Never constructs the mutating storage implementations. */
final class ReadOnlyStore {
  static final long MAGIC = 0xf00d0000d00f0000L;
  static final long OPEN = 0xEFFFFFFFFFFFFFFFL;
  static final int INDEX_HEADER = 48;
  static final int DATA_HEADER = 24;
  private final long maxRecordBytes;
  private final MessageDecoder decoder;
  private final Sink sink;

  interface Sink {
    void report(JsonObject report) throws IOException;
    void event(JsonObject event) throws IOException;
  }

  ReadOnlyStore(long maxRecordBytes, MessageDecoder decoder, Sink sink) {
    this.maxRecordBytes = maxRecordBytes;
    this.decoder = decoder;
    this.sink = sink;
  }

  static final class Stats {
    long active;
    long expired;
    long deleted;
    long unused;
    long decoded;
    long valid;
    long errors;
    long warnings;
    long bytes;
    long referencedBytes;
    String indexState = "UNKNOWN";
    String dataState = "UNKNOWN";
    long start;
    long end;
  }

  Stats inspect(Path index, String topic) throws IOException {
    Stats stats = new Stats();
    Path data = index.resolveSibling(index.getFileName() + "_data");
    if (topic == null) {
      try {
        topic = StoreMetadata.topic(index.getParent());
      } catch (IOException e) {
        finding(stats, index, "WARNING", "METADATA_UNREADABLE", -1, e.toString());
      }
    }
    try {
      inspectFiles(index, data, topic, stats);
    } catch (OutputFailure e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      finding(stats, index, "ERROR", "STORE_UNREADABLE", -1, e.toString());
    }
    JsonObject summary = base(index, "store");
    summary.addProperty("indexState", stats.indexState);
    summary.addProperty("dataState", stats.dataState);
    summary.addProperty("validation", decoder == null ? "STRUCTURAL_ONLY" : "STRUCTURAL_AND_MESSAGE");
    summary.addProperty("status", stats.errors > 0 ? "ERROR" : stats.warnings > 0 ? "WARNING" : "CHECKED");
    summary.addProperty("topic", topic);
    summary.addProperty("startKey", stats.start);
    summary.addProperty("endKey", stats.end);
    summary.addProperty("sizeBytes", stats.bytes);
    summary.addProperty("activeEvents", stats.active);
    summary.addProperty("expiredEvents", stats.expired);
    summary.addProperty("deletedSlots", stats.deleted);
    summary.addProperty("unusedSlots", stats.unused);
    summary.addProperty("validRecords", stats.valid);
    summary.addProperty("decodedEvents", stats.decoded);
    summary.addProperty("referencedBytes", stats.referencedBytes);
    summary.addProperty("errors", stats.errors);
    summary.addProperty("warnings", stats.warnings);
    sink.report(summary);
    return stats;
  }

  static final class OutputFailure extends IOException {
    OutputFailure(IOException cause) { super("Cannot write inspection output", cause); }
  }

  private void inspectFiles(Path index, Path data, String topic, Stats stats) throws IOException {
    BasicFileAttributes beforeIndex = attributes(index);
    stats.bytes = beforeIndex.size();
    try (FileChannel idx = open(index)) {
      stats.indexState = header(idx, index, stats);
      ByteBuffer bounds = read(idx, 24, 24);
      long capacity = bounds.getLong();
      stats.start = bounds.getLong();
      stats.end = bounds.getLong();
      if (capacity <= 0 || stats.start < 0 || stats.end < stats.start || stats.end - stats.start >= capacity) {
        throw new IOException("Invalid index capacity/key range");
      }
      long slots = stats.end - stats.start + 1;
      long available = (idx.size() - INDEX_HEADER) / IndexRecord.HEADER_SIZE;
      if (slots > available) {
        finding(stats, index, "ERROR", "TRUNCATED_INDEX", idx.size(), "Expected " + slots + " slots; only " + available + " complete slots");
      }
      if ((idx.size() - INDEX_HEADER) % IndexRecord.HEADER_SIZE != 0) {
        finding(stats, index, "ERROR", "PARTIAL_INDEX_SLOT", idx.size(), "Trailing partial index slot");
      }
      if (!Files.exists(data, LinkOption.NOFOLLOW_LINKS)) {
        finding(stats, data, "ERROR", "MISSING_DATA", -1, "Index has no data file");
        return;
      }
      BasicFileAttributes beforeData = attributes(data);
      stats.bytes += beforeData.size();
      try (FileChannel dat = open(data)) {
        if (dat.size() > 0 && read(dat, 0, 1).get() == '#') {
          stats.dataState = "ARCHIVED";
          finding(stats, data, "WARNING", "ARCHIVE_NOT_INSPECTED", 0,
              "Deferred/compressed/migrated/S3 placeholder: restore an offline copy with matching storage tooling before inspection");
          scanIndex(idx, null, index, topic, Math.min(slots, available), stats);
        } else {
          stats.dataState = header(dat, data, stats);
          scanIndex(idx, dat, index, topic, Math.min(slots, available), stats);
          scanData(dat, data, stats);
        }
      }
      changed(data, beforeData, stats);
    }
    changed(index, beforeIndex, stats);
  }

  private void scanIndex(FileChannel idx, FileChannel data, Path index, String topic, long slots, Stats stats) throws IOException {
    long now = System.currentTimeMillis();
    for (long slot = 0; slot < slots; slot++) {
      long offset = INDEX_HEADER + slot * IndexRecord.HEADER_SIZE;
      IndexRecord record = new IndexRecord(stats.start + slot, read(idx, offset, IndexRecord.HEADER_SIZE));
      if (record.getPosition() == 0) {
        if (record.getLength() > 0) stats.deleted++; else stats.unused++;
        if (record.getExpiry() != 0 || record.getLocationId() != 0) {
          finding(stats, index, "ERROR", "INVALID_EMPTY_SLOT", offset, "Empty slot contains nonzero expiry/location");
        }
        continue;
      }
      stats.active++;
      if (record.getExpiry() > 0 && record.getExpiry() <= now) stats.expired++;
      if (record.getLocationId() != 0) {
        finding(stats, index, "ERROR", "UNSUPPORTED_LOCATION", offset, "Location ID " + record.getLocationId());
        continue;
      }
      if (data == null) continue;
      ByteBuffer[] buffers;
      JsonObject event = null;
      try {
        buffers = frame(data, record.getPosition(), record.getLength(), decoder != null);
        stats.valid++;
        stats.referencedBytes += record.getLength();
        if (decoder != null) {
          event = decoder.decode(buffers, record.getKey());
          if (event.get("expiry").getAsLong() != record.getExpiry()) {
            throw new IOException("Message expiry differs from index expiry");
          }
        }
      } catch (IOException | RuntimeException e) {
        finding(stats, index, "ERROR", "INVALID_RECORD", offset,
            "key=" + record.getKey() + ", dataOffset=" + record.getPosition() + ": " + e);
        continue;
      }
      if (event != null) {
        event.addProperty("topic", topic);
        event.addProperty("storageFile", index.toString());
        event.addProperty("storageKey", record.getKey());
        event.addProperty("storageOffset", record.getPosition());
        event.addProperty("storageLength", record.getLength());
        event.addProperty("storageExpired", record.getExpiry() > 0 && record.getExpiry() <= now);
        sink.event(event);
        stats.decoded++;
      }
    }
  }

  /** Also scans non-indexed physical records: deleted data and an incomplete append remain visible. */
  private void scanData(FileChannel data, Path path, Stats stats) throws IOException {
    long offset = DATA_HEADER;
    long records = 0;
    while (offset < data.size()) {
      try {
        int length = frameLength(data, offset);
        frame(data, offset, length, false);
        offset += length;
        records++;
      } catch (IOException | RuntimeException e) {
        finding(stats, path, "ERROR", "INVALID_PHYSICAL_RECORD", offset, e.toString());
        break; // No record magic: guessing the next boundary could invent recovered messages.
      }
    }
    JsonObject report = base(path, "physicalData");
    report.addProperty("physicalRecords", records);
    report.addProperty("scannedBytes", offset);
    report.addProperty("sizeBytes", data.size());
    report.addProperty("complete", offset == data.size());
    sink.report(report);
  }

  private int frameLength(FileChannel channel, long offset) throws IOException {
    if (offset < DATA_HEADER) throw new IOException("Record points inside header");
    ByteBuffer head = read(channel, offset, 8);
    int declared = head.getInt();
    int count = head.getInt();
    if (declared < 4 || count < 1 || count > 1024) throw new IOException("Invalid record length/buffer count");
    long total = (long) declared + 4L + 4L * count;
    if (total > maxRecordBytes || total > Integer.MAX_VALUE || total > channel.size() - offset) {
      throw new IOException("Record truncated or exceeds --max-record-bytes: " + total);
    }
    return (int) total;
  }

  private ByteBuffer[] frame(FileChannel channel, long offset, int expectedLength, boolean load) throws IOException {
    int length = frameLength(channel, offset);
    if (expectedLength != length) throw new IOException("Index length " + expectedLength + " differs from frame length " + length);
    int count = read(channel, offset + 4, 4).getInt();
    ByteBuffer lengths = read(channel, offset + 8, count * 4);
    long payloadStart = offset + 8L + count * 4L;
    long total = 8L + count * 4L;
    int[] sizes = new int[count];
    for (int i = 0; i < count; i++) {
      sizes[i] = lengths.getInt();
      if (sizes[i] < 0) throw new IOException("Negative buffer length");
      total += sizes[i];
      if (total > length) throw new IOException("Buffer sizes exceed record length");
    }
    if (total != length) throw new IOException("Buffer sizes disagree with record length");
    if (!load) return null;
    ByteBuffer[] buffers = new ByteBuffer[count];
    for (int i = 0; i < count; i++) {
      buffers[i] = read(channel, payloadStart, sizes[i]);
      payloadStart += sizes[i];
    }
    return buffers;
  }

  private String header(FileChannel channel, Path path, Stats stats) throws IOException {
    ByteBuffer header = read(channel, 0, DATA_HEADER);
    long state = header.getLong();
    if (header.getLong() != MAGIC) throw new IOException("Invalid file magic");
    if (header.getDouble() != 1.0) throw new IOException("Unsupported storage version");
    if (state == 0) return "CLOSED";
    if (state == OPEN) {
      finding(stats, path, "WARNING", "OPEN_STORE", 0, "Open marker: live store or unclean shutdown; does not alone prove corruption");
      return "OPEN";
    }
    throw new IOException("Unknown open/closed state: " + Long.toHexString(state));
  }

  private void changed(Path path, BasicFileAttributes before, Stats stats) throws IOException {
    BasicFileAttributes after = attributes(path);
    if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
      finding(stats, path, "ERROR", "CHANGED_DURING_SCAN", -1, "Input changed; results are not a consistent snapshot");
    }
  }

  private void finding(Stats stats, Path file, String severity, String code, long offset, String detail) throws IOException {
    if (severity.equals("ERROR")) stats.errors++; else stats.warnings++;
    JsonObject report = base(file, "finding");
    report.addProperty("severity", severity);
    report.addProperty("code", code);
    report.addProperty("offset", offset);
    report.addProperty("detail", detail);
    sink.report(report);
  }

  static JsonObject base(Path file, String type) {
    JsonObject result = new JsonObject();
    result.addProperty("type", type);
    result.addProperty("file", file.toString());
    return result;
  }

  static BasicFileAttributes attributes(Path path) throws IOException {
    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isRegularFile()) throw new IOException("Not a regular file (symlinks are not followed): " + path);
    return attrs;
  }

  static FileChannel open(Path path) throws IOException {
    return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
  }

  static ByteBuffer read(FileChannel channel, long offset, int size) throws IOException {
    if (offset < 0 || size < 0 || size > channel.size() - offset) throw new EOFException("Truncated data at " + offset + " (need " + size + " bytes)");
    ByteBuffer buffer = ByteBuffer.allocate(size);
    while (buffer.hasRemaining()) {
      int count = channel.read(buffer, offset + buffer.position());
      if (count <= 0) throw new EOFException("Incomplete read at " + (offset + buffer.position()));
    }
    return buffer.flip();
  }
}
