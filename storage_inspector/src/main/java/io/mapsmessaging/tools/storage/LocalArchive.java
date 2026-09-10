/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/** Reads CompressionRecord and its local GZIP sidecar without restoring over original evidence. */
final class LocalArchive implements AutoCloseable {
  final Path sidecar;
  final FileChannel channel;
  final boolean digestVerified;
  private final Path temporary;

  private LocalArchive(Path sidecar, Path temporary, FileChannel channel, boolean digestVerified) {
    this.sidecar = sidecar;
    this.temporary = temporary;
    this.channel = channel;
    this.digestVerified = digestVerified;
  }

  static final class Unavailable extends IOException {
    Unavailable(String message) { super(message); }
  }

  static LocalArchive open(Path placeholder, long maxBytes, Path inputRoot) throws IOException {
    String[] lines;
    try (FileChannel source = ReadOnlyStore.open(placeholder)) {
      if (source.size() > 16384) throw new IOException("Archive placeholder exceeds 16 KiB");
      lines = StandardCharsets.UTF_8.decode(ReadOnlyStore.read(source, 0, (int) source.size())).toString().split("\\R");
    }
    if (lines.length == 0) throw new IOException("Empty archive placeholder");
    if (lines[0].equals("# Migration file place holder") || lines[0].equals("# s3 bucket place holder")) {
      throw new Unavailable(lines[0] + ": destination/configuration or remote data required; local data not available");
    }
    if (!lines[0].equals("# Zip file place holder") || lines.length != 5) {
      throw new IOException("Invalid CompressionRecord placeholder");
    }
    long length;
    MessageDigest digest;
    try {
      length = Long.parseLong(lines[3]);
      LocalDateTime.parse(lines[4]);
      if (length < ReadOnlyStore.DATA_HEADER) throw new IllegalArgumentException("Invalid archive length");
      if (length > maxBytes) throw new Unavailable("Archive exceeds --max-archive-bytes: " + length);
      digest = lines[1].isEmpty() || lines[1].equalsIgnoreCase("none") ? null : MessageDigest.getInstance(lines[1]);
      if (digest == null && !lines[2].isEmpty()) throw new IllegalArgumentException("Hash present without a digest algorithm");
    } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
      throw new IOException("Invalid archive metadata", e);
    }
    Path sidecar = placeholder.resolveSibling(placeholder.getFileName() + "_zip");
    var before = ReadOnlyStore.attributes(sidecar);
    Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
    if (tempDirectory.startsWith(inputRoot)) throw new IOException("Temporary directory must be outside the input tree; configure -Djava.io.tmpdir");
    Path temporary = Files.createTempFile(tempDirectory, "maps-inspect-", ".data");
    boolean success = false;
    try {
      try (FileChannel input = ReadOnlyStore.open(sidecar);
           GZIPInputStream gzip = new GZIPInputStream(Channels.newInputStream(input));
           var output = Files.newOutputStream(temporary)) {
        byte[] bytes = new byte[64 * 1024];
        long written = 0;
        int count;
        while ((count = gzip.read(bytes)) != -1) {
          if (count > length - written) throw new IOException("Archive expands beyond its declared length");
          output.write(bytes, 0, count);
          if (digest != null) digest.update(bytes, 0, count);
          written += count;
        }
        if (written != length) throw new IOException("Archive length mismatch: expected " + length + ", got " + written);
      }
      if (digest != null && !Base64.getEncoder().encodeToString(digest.digest()).equals(lines[2])) {
        throw new IOException("Archive digest mismatch");
      }
      var after = ReadOnlyStore.attributes(sidecar);
      if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
        throw new IOException("Archive changed during inspection");
      }
      FileChannel channel = ReadOnlyStore.open(temporary);
      success = true;
      return new LocalArchive(sidecar, temporary, channel, digest != null);
    } finally {
      if (!success) Files.deleteIfExists(temporary);
    }
  }

  @Override public void close() throws IOException {
    try { channel.close(); } finally { Files.deleteIfExists(temporary); }
  }
}
