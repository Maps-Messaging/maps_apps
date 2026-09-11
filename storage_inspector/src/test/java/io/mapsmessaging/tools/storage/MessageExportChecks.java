/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import com.google.gson.JsonObject;
import io.mapsmessaging.storage.Storable;
import io.mapsmessaging.storage.StorableFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/** Tests export mapping independently of the optional server runtime. */
public final class MessageExportChecks {
  private MessageExportChecks() {}

  public static void main(String[] args) throws Exception {
    for (byte[] payload : new byte[][] {"{\"heading\":123}".getBytes(StandardCharsets.UTF_8), new byte[]{0, (byte) 255, 1}}) {
      MessageDecoder decoder = new MessageDecoder(new StorableFactory<FixtureMessage>() {
        public FixtureMessage unpack(ByteBuffer[] buffers) { return new FixtureMessage(payload); }
        public ByteBuffer[] pack(FixtureMessage message) { throw new UnsupportedOperationException(); }
      });
      JsonObject event = decoder.decode(new ByteBuffer[0], 42);
      if (!Arrays.equals(payload, Base64.getDecoder().decode(event.get("opaqueData").getAsString()))) {
        throw new AssertionError("Payload must survive base64 export byte for byte");
      }
      if (event.get("identifier").getAsLong() != 42 || event.get("creation").getAsLong() != 123456
          || !event.get("retain").getAsBoolean() || !event.get("meta").getAsJsonObject().has("source")) {
        throw new AssertionError("Message metadata lost");
      }
      try {
        decoder.decode(new ByteBuffer[0], 43);
        throw new AssertionError("Mismatched message key must fail");
      } catch (IOException expected) {
        // Corrupt or misplaced message must not be exported under the wrong key.
      }
    }
    System.out.println("Message export: JSON/binary payloads, metadata and mismatched keys passed");
  }

  public static final class FixtureMessage implements Storable {
    private final byte[] payload;
    public FixtureMessage(byte[] payload) { this.payload = payload; }
    public long getKey() { return 42; }
    public long getIdentifier() { return 42; }
    public long getCreation() { return 123456; }
    public long getExpiry() { return 0; }
    public long getDelayed() { return 0; }
    public String getPriority() { return "NORMAL"; }
    public String getQualityOfService() { return "AT_LEAST_ONCE"; }
    public String getResponseTopic() { return "/reply"; }
    public String getContentType() { return "application/octet-stream"; }
    public String getSchemaId() { return null; }
    public Map<String, String> getMeta() { return Map.of("source", "test"); }
    public Map<String, String> getDataMap() { return Map.of("sensor", "sonar"); }
    public boolean isRetain() { return true; }
    public boolean isUTF8() { return false; }
    public boolean isCorrelationDataByteArray() { return true; }
    public byte[] getOpaqueData() { return payload; }
    public byte[] getCorrelationData() { return new byte[]{1, 2}; }
  }
}
