package io.mapsmessaging.tools.mqttreplay;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReplayInputValidationTest {
  @TempDir Path directory;

  @Test
  void acceptsRelativeAndAbsoluteMqttPrefixesWithoutChangingTheirMeaning() {
    ReplayOptions relative = ReplayOptions.parse(new String[]{"--dir", directory.toString(), "--broker", "tcp://localhost:1883", "--session-prefix", "4817/catl/maps/"});
    ReplayOptions absolute = ReplayOptions.parse(new String[]{"--dir", directory.toString(), "--broker", "tcp://localhost:1883", "--session-prefix", "/4817/catl/maps/"});
    assertEquals("4817/catl/maps", relative.sessionPrefix());
    assertEquals("/4817/catl/maps", absolute.sessionPrefix());
    assertThrows(IllegalArgumentException.class, () -> ReplayOptions.parse(new String[]{"--dir", directory.toString(), "--broker", "tcp://localhost:1883", "--session-prefix", "4817/#"}));
  }

  @Test
  void normalizesBrokerAndObserverRootPaths() {
    ReplayOptions options = ReplayOptions.parse(new String[]{"--dir", directory.toString(), "--broker", "tcp://localhost:1883/", "--observe", "tcp://s1:1883/", "--observe", "ssl://s2:8883"});
    assertEquals("tcp://localhost:1883", options.broker());
    assertEquals("tcp://s1:1883", options.observeUrls().get(0));
    assertEquals("ssl://s2:8883", options.observeUrls().get(1));
    assertThrows(IllegalArgumentException.class, () -> ReplayOptions.parse(new String[]{"--dir", directory.toString(), "--broker", "tcp://localhost:1883/other"}));
  }
}
