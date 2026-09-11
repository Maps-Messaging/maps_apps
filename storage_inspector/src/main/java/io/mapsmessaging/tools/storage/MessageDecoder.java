/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.mapsmessaging.storage.Storable;
import io.mapsmessaging.storage.StorableFactory;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.Base64;

/** Loads the installed server's decoder without starting a server or opening storage. */
final class MessageDecoder {
  private static final Gson JSON = new GsonBuilder().serializeNulls().create();
  private final StorableFactory<?> factory;

  MessageDecoder() throws ReflectiveOperationException {
    factory = (StorableFactory<?>) Class.forName("io.mapsmessaging.api.message.MessageFactory")
        .getConstructor().newInstance();
  }

  MessageDecoder(StorableFactory<?> factory) {
    this.factory = factory;
  }

  JsonObject decode(ByteBuffer[] buffers, long expectedKey) throws IOException {
    try {
      Storable message = factory.unpack(buffers);
      if (message.getKey() != expectedKey) {
        throw new IOException("Message key " + message.getKey() + " differs from index key " + expectedKey);
      }
      JsonObject result = new JsonObject();
      for (String property : new String[]{"Identifier", "Creation", "Expiry", "Delayed", "Priority",
          "QualityOfService", "ResponseTopic", "ContentType", "SchemaId", "Meta", "DataMap"}) {
        Object value = message.getClass().getMethod("get" + property).invoke(message);
        result.add(Character.toLowerCase(property.charAt(0)) + property.substring(1), JSON.toJsonTree(value));
      }
      for (String property : new String[]{"Retain", "UTF8", "CorrelationDataByteArray"}) {
        Object value = message.getClass().getMethod("is" + property).invoke(message);
        result.add(property.equals("UTF8") ? "utf8" : Character.toLowerCase(property.charAt(0)) + property.substring(1), JSON.toJsonTree(value));
      }
      for (String property : new String[]{"OpaqueData", "CorrelationData"}) {
        byte[] value = (byte[]) message.getClass().getMethod("get" + property).invoke(message);
        result.addProperty(Character.toLowerCase(property.charAt(0)) + property.substring(1),
            value == null ? null : Base64.getEncoder().encodeToString(value));
      }
      return result;
    } catch (ReflectiveOperationException | RuntimeException e) {
      Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
      throw new IOException("MAPS message decode failed: " + cause, cause);
    }
  }
}
