/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** ResourceProperties metadata; this is not a StorageConfig. Never follows its UUID to another path. */
final class StoreMetadata {
  private final JsonObject values;

  private StoreMetadata(JsonObject values) {
    this.values = values;
  }

  String topic() {
    return values.get("resourceName").getAsString();
  }

  JsonObject json() {
    return values.deepCopy();
  }

  static String topic(Path directory) throws IOException {
    StoreMetadata metadata = load(directory);
    return metadata == null ? null : metadata.topic();
  }

  static StoreMetadata load(Path directory) throws IOException {
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
    // ResourceProperties is the only bean tag written by ResourceProperties.write().
    text = text.replaceFirst("^!!io\\.mapsmessaging\\.engine\\.resources\\.ResourceProperties[ \\t]*(?=\\r?\\n|\\{|$)", "");
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(10);
    options.setNestingDepthLimit(20);
    options.setCodePointLimit(1024 * 1024);
    try {
      Object loaded = new Yaml(new SafeConstructor(options)).load(text);
      if (!(loaded instanceof Map<?, ?> fields) || !(fields.get("resourceName") instanceof String name) || name.isBlank()) {
        throw new IOException("resource.yaml has no nonempty string resourceName");
      }
      JsonObject values = new JsonObject();
      values.addProperty("metadataFile", metadata.toString());
      for (String field : new String[]{"resourceName", "type", "uuid", "date", "buildDate", "buildVersion", "schemaId", "schema"}) {
        values.add(field, jsonValue(fields.get(field), 0));
      }
      String uuid = fields.get("uuid") instanceof String value ? value : null;
      try {
        // ResourceFactory.scan() expects signed most-significant:least-significant long values.
        if (uuid == null || !uuid.matches("-?[0-9]+:-?[0-9]+")) throw new IllegalArgumentException("Invalid UUID pair");
        String[] pair = uuid.split(":", -1);
        values.addProperty("normalizedUuid", new UUID(Long.parseLong(pair[0]), Long.parseLong(pair[1])).toString());
        values.addProperty("serverIdentityValid", true);
      } catch (IllegalArgumentException e) {
        values.add("normalizedUuid", JsonNull.INSTANCE);
        values.addProperty("serverIdentityValid", false);
      }
      return new StoreMetadata(values);
    } catch (RuntimeException e) {
      throw new IOException("Cannot safely parse resource.yaml", e);
    }
  }

  private static JsonElement jsonValue(Object value, int depth) throws IOException {
    if (depth > 20) throw new IOException("Recursive or excessively nested metadata");
    if (value == null) return JsonNull.INSTANCE;
    if (value instanceof String string) return new JsonPrimitive(string);
    if (value instanceof Boolean bool) return new JsonPrimitive(bool);
    if (value instanceof Number number) return new JsonPrimitive(number);
    if (value instanceof Date date) return new JsonPrimitive(date.toInstant().toString());
    if (value instanceof Map<?, ?> map) {
      JsonObject result = new JsonObject();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw new IOException("Non-string metadata map key");
        result.add(key, jsonValue(entry.getValue(), depth + 1));
      }
      return result;
    }
    if (value instanceof Iterable<?> list) {
      JsonArray result = new JsonArray();
      for (Object item : list) result.add(jsonValue(item, depth + 1));
      return result;
    }
    throw new IOException("Unsupported metadata value type: " + value.getClass().getName());
  }
}
