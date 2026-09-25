package io.mapsmessaging.tools.testlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabConfigTest {

  @TempDir Path tempDir;

  @Test
  void loadsRelativeRootAndDefaults() throws Exception {
    Path file = tempDir.resolve("lab.json");
    Files.writeString(
        file,
        """
        {
          "root": "work",
          "instances": {
            "alpha": {
              "command": ["java", "-version"]
            }
          }
        }
        """);

    LabConfig config = LabConfig.load(file);

    assertEquals(tempDir.resolve("work").toAbsolutePath().normalize(), config.root());
    assertEquals("127.0.0.1", config.bindAddress());
    assertEquals(8091, config.port());
    assertEquals("127.0.0.1", config.requireInstance("alpha").mqttHost());
    assertEquals(1883, config.requireInstance("alpha").mqttPort());
  }

  @Test
  void loadsDockerInstanceWithDebugSettings() throws Exception {
    Path file = tempDir.resolve("docker-lab.json");
    Files.writeString(
        file,
        """
        {
          "root": "work",
          "dockerCommand": "docker",
          "instances": {
            "ralf-a": {
              "provider": "docker",
              "image": "mapsmessaging/server:test",
              "network": "ralf-lab",
              "ports": ["18831:1883"],
              "debugPort": 5005,
              "debugSuspend": true,
              "containerCommand": ["--config", "/opt/maps/config"]
            }
          }
        }
        """);

    LabConfig config = LabConfig.load(file);
    LabConfig.InstanceConfig instance = config.requireInstance("ralf-a");

    assertEquals("docker", instance.provider());
    assertEquals("mapsmessaging/server:test", instance.image());
    assertEquals("ralf-lab", instance.network());
    assertEquals(5005, instance.debugPort());
    assertTrue(instance.debugSuspend());
  }

  @Test
  void rejectsInvalidInstanceNames() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new LabConfig(
                    tempDir,
                    "127.0.0.1",
                    8091,
                    "mosquitto_pub",
                    "mosquitto_sub",
                    java.util.Map.of(
                        "../escape",
                        new LabConfig.InstanceConfig(
                            java.util.List.of("java"), java.util.Map.of(), "localhost", 1883))));

    assertTrue(exception.getMessage().contains("Invalid instance name"));
  }

  @Test
  void rejectsInstanceWithoutCommand() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new LabConfig(
                tempDir,
                "127.0.0.1",
                8091,
                "mosquitto_pub",
                "mosquitto_sub",
                java.util.Map.of(
                    "alpha",
                    new LabConfig.InstanceConfig(
                        java.util.List.of(), java.util.Map.of(), "localhost", 1883))));
  }
}
