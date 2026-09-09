/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class InputFileResolver {

  List<Path> resolve(Path input) throws IOException {
    if (!Files.exists(input)) {
      throw new IOException("Input does not exist: " + input);
    }
    if (Files.isRegularFile(input)) {
      return List.of(input.toAbsolutePath().normalize());
    }

    try (Stream<Path> paths = Files.walk(input)) {
      List<Path> files = paths
          .filter(Files::isRegularFile)
          .filter(this::isNdjsonFile)
          .map(path -> path.toAbsolutePath().normalize())
          .sorted(Comparator.naturalOrder())
          .toList();
      if (files.isEmpty()) {
        throw new IOException("Directory contains no .ndjson, .jsonl, .json or gzip-compressed equivalents: " + input);
      }
      return files;
    }
  }

  private boolean isNdjsonFile(Path path) {
    String filename = path.getFileName().toString().toLowerCase();
    return filename.endsWith(".ndjson")
        || filename.endsWith(".jsonl")
        || filename.endsWith(".json")
        || filename.endsWith(".ndjson.gz")
        || filename.endsWith(".jsonl.gz")
        || filename.endsWith(".json.gz");
  }
}
