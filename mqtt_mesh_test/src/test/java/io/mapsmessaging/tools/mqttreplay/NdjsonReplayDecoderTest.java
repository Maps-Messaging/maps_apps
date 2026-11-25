package io.mapsmessaging.tools.mqttreplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class NdjsonReplayDecoderTest {

  @Test
  void decodesRepmusEnvelopeWithoutReplacingOuterMetadata() {
    String line = """
        {"correlationDataByteArray":true,"lastMessage":true,"priority":"NORMAL","utf8":false,
        "expiry":0,"qualityOfService":"AT_MOST_ONCE","schemaId":"10000000-0000-1000-a000-100000000003",
        "meta":{"protocol":"MQTT","version":"5","sessionId":"edge-maps-halpi-27de21dc-aggregator-up",
        "time_ms":"1787051908510"},"dataMap":{},"retain":false,"storeOffline":false,"delayed":0,
        "opaqueData":"cm91dGUtMTc4NzA1MTkwNC03MjgzMy0x","creation":1787051908510,"identifier":1,
        "receivedTimestamp":"2026-08-18T11:18:28.536304603Z",
        "topic":"4817/catl/maps/edge-maps-halpi-27de21dc/routetest/route-1787051904-72833-1"}
        """;
    ReplayEvent event = NdjsonReplayDecoder.decode(line, 2);
    assertEquals("4817/catl/maps/edge-maps-halpi-27de21dc/routetest/route-1787051904-72833-1", event.topic());
    assertEquals(Instant.parse("2026-08-18T11:18:28.536304603Z"), event.timestamp());
    assertArrayEquals("route-1787051904-72833-1".getBytes(StandardCharsets.UTF_8), event.payload());
    assertEquals(0, event.qos());
    assertFalse(event.retained());
  }

  @Test
  void preservesBinaryOpaqueDataAndRetainFlag() {
    byte[] payload = {0, (byte) 0xff, (byte) 0xfe, 42, 0};
    String line = "{\"topic\":\"sensor/data\",\"receivedTimestamp\":\"2026-09-01T00:00:00Z\","
        + "\"qualityOfService\":\"EXACTLY_ONCE\",\"retain\":true,\"opaqueData\":\""
        + Base64.getEncoder().encodeToString(payload) + "\"}";
    ReplayEvent event = NdjsonReplayDecoder.decode(line, 1);
    assertArrayEquals(payload, event.payload());
    assertEquals(2, event.qos());
    assertTrue(event.retained());
  }

  @Test
  void preservesLegacyJsonPayloadAndQosFields() {
    ReplayEvent event = NdjsonReplayDecoder.decode(
        "{\"topic\":\"a/b\",\"receivedTimestamp\":\"2026-09-01T00:00:00Z\","
            + "\"qos\":\"AT_LEAST_ONCE\",\"retained\":true,\"payload\":{\"value\":42}}", 0);
    assertArrayEquals("{\"value\":42}".getBytes(StandardCharsets.UTF_8), event.payload());
    assertEquals(1, event.qos());
    assertTrue(event.retained());
  }
}
