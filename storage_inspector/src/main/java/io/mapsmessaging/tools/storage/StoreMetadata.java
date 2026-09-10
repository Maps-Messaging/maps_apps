/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class StoreMetadata {
  private StoreMetadata() {}

  static String topic(Path directory) throws IOException {
    Path metadata = directory.resolve("resource.yaml");
    if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS) && directory.getFileName().toString().equals("message.data")) {
      metadata = directory.getParent().resolve("resource.yaml");
    }
    if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) return null;
    long size = ReadOnlyStore.attributes(metadata).size();
    if (size > 1024 * 1024) throw new IOException("resource.yaml exceeds 1 MiB");
    String text;
    try (var channel = ReadOnlyStore.open(metadata)) {
      text = StandardCharsets.UTF_8.decode(ReadOnlyStore.read(channel, 0, (int) size)).toString();
    }
    // The server dumps this bean with a global tag. Remove only that exact root tag;
    // all other object tags remain rejected by SafeConstructor.
    text = text.replaceFirst("^!!io\\.mapsmessaging\\.engine\\.resources\\.ResourceProperties[ \\t]*(?:\\r?\\n|$)", "");
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(10);
    options.setNestingDepthLimit(20);
    options.setCodePointLimit(1024 * 1024);
    try {
      Object loaded = new Yaml(new SafeConstructor(options)).load(text);
      if (!(loaded instanceof Map<?, ?> values) || !(values.get("resourceName") instanceof String name)) {
        throw new IOException("resource.yaml has no string resourceName");
      }
      return name;
    } catch (RuntimeException e) {
      throw new IOException("Cannot safely parse resource.yaml", e);
    }
  }
}
