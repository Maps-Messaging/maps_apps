package io.mapsmessaging.tools.mqttreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MqttVersionTest {
  @TempDir Path directory;

  @Test
  void defaultAndExplicitVersions() {
    String[] base = {"--dir", directory.toString(), "--broker", "tcp://localhost:1883"};
    assertEquals("3.1.1", ReplayOptions.parse(base).mqttVersion());
    for (String version : new String[]{"3.1", "3.1.1", "5.0"}) {
      assertEquals(version, ReplayOptions.parse(new String[]{base[0], base[1], base[2], base[3], "--mqtt-version", version}).mqttVersion());
    }
    assertThrows(IllegalArgumentException.class, () -> ReplayOptions.parse(
        new String[]{base[0], base[1], base[2], base[3], "--mqtt-version", "4"}));
  }

  @Test
  void selectsClientWithoutConnecting() throws Exception {
    for (String version : new String[]{"3.1", "3.1.1", "5.0"}) {
      try (MqttWireClient client = MqttWireClient.create(version, "tcp://localhost:1883", "test-client", 1000, null, null)) {
        if ("5.0".equals(version)) {
          assertInstanceOf(Paho5WireClient.class, client);
        } else {
          assertInstanceOf(Paho3WireClient.class, client);
        }
      }
    }
  }

  @Test
  void rejectsFailedSubackAcrossProtocols() {
    assertDoesNotThrow(() -> MqttWireClient.requireSuback("a/#", 1, new int[]{0}));
    assertDoesNotThrow(() -> MqttWireClient.requireSuback("a/#", 1, new int[]{1}));
    assertThrows(IllegalStateException.class, () -> MqttWireClient.requireSuback("a/#", 1, new int[]{128}));
    assertThrows(IllegalStateException.class, () -> MqttWireClient.requireSuback("a/#", 1, new int[]{135}));
    assertThrows(IllegalStateException.class, () -> MqttWireClient.requireSuback("a/#", 1, null));
  }
}
