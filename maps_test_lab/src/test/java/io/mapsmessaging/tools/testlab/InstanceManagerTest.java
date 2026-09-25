package io.mapsmessaging.tools.testlab;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceManagerTest {

  @TempDir Path tempDir;

  @Test
  void startsStopsAndIsolatesTwoInstances() throws Exception {
    try (InstanceManager manager = new InstanceManager(config("alpha", "beta"))) {
      Map<String, Object> alpha = manager.start("alpha");
      Map<String, Object> beta = manager.start("beta");

      assertTrue((Boolean) alpha.get("running"));
      assertTrue((Boolean) beta.get("running"));
      assertNotEquals(alpha.get("pid"), beta.get("pid"));
      assertNotEquals(alpha.get("instanceDir"), beta.get("instanceDir"));

      assertThrows(IllegalStateException.class, () -> manager.start("alpha"));

      assertFalse((Boolean) manager.stop("alpha").get("running"));
      assertFalse((Boolean) manager.stop("alpha").get("running"));
      assertFalse((Boolean) manager.stop("beta").get("running"));
    }
  }

  @Test
  void deletesInstanceWorkspaceAndAllowsCleanRecreation() throws Exception {
    try (InstanceManager manager = new InstanceManager(config("alpha"))) {
      manager.start("alpha");
      Path instanceDir = Path.of((String) manager.status("alpha").get("instanceDir"));
      Path configDir = Path.of((String) manager.status("alpha").get("configDir"));
      Files.writeString(configDir.resolve("server.yaml"), "name: alpha");

      Map<String, Object> deleted = manager.delete("alpha");

      assertTrue((Boolean) deleted.get("deleted"));
      assertFalse(Files.exists(instanceDir));

      Map<String, Object> restarted = manager.start("alpha");
      assertTrue((Boolean) restarted.get("running"));
      assertTrue(Files.exists(Path.of((String) restarted.get("configDir"))));
      assertFalse(Files.exists(Path.of((String) restarted.get("configDir")).resolve("server.yaml")));
    }
  }

  @Test
  void rejectsConfigPathTraversal() throws Exception {
    try (InstanceManager manager = new InstanceManager(config("alpha"))) {
      manager.start("alpha");
      Path configDir = Path.of((String) manager.status("alpha").get("configDir"));
      Files.writeString(configDir.resolve("server.yaml"), "name: alpha");

      assertTrue(manager.readConfig("alpha", "server.yaml").contains("alpha"));
      assertThrows(
          IllegalArgumentException.class,
          () -> manager.readConfig("alpha", "../data/secret.txt"));
    }
  }

  @Test
  void writesConfigAndCreatesDownloadableEvidenceArchive() throws Exception {
    try (InstanceManager manager = new InstanceManager(config("alpha"))) {
      manager.writeConfig("alpha", "nested/server.yaml", "name: alpha", "utf8", false);

      assertTrue(manager.readConfig("alpha", "nested/server.yaml").contains("alpha"));
      assertThrows(
          IllegalStateException.class,
          () ->
              manager.writeConfig(
                  "alpha", "nested/server.yaml", "name: changed", "utf8", false));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              manager.writeConfig(
                  "alpha", "../escape.yaml", "bad", "utf8", true));

      Map<String, Object> archive = manager.createEvidenceArchive("alpha", "config-test");
      Path zip = Path.of((String) archive.get("path"));
      assertTrue(Files.exists(zip));
      assertTrue(Files.size(zip) > 0);
    }
  }

  @Test
  void capturesEvidenceWithConfigLogsAndManifest() throws Exception {
    try (InstanceManager manager = new InstanceManager(config("alpha"))) {
      manager.start("alpha");
      Path configDir = Path.of((String) manager.status("alpha").get("configDir"));
      Files.writeString(configDir.resolve("server.yaml"), "name: alpha");

      Thread.sleep(100);
      Path evidence = manager.createEvidence("alpha", "restart-check");

      assertTrue(Files.exists(evidence.resolve("manifest.json")));
      assertTrue(Files.exists(evidence.resolve("config/server.yaml")));
      assertTrue(Files.exists(evidence.resolve("logs/maps.log")));
    }
  }

  @Test
  void reportsFailedProcessStartup() throws Exception {
    LabConfig config =
        new LabConfig(
            tempDir,
            "127.0.0.1",
            8091,
            "mosquitto_pub",
            "mosquitto_sub",
            Map.of(
                "broken",
                new LabConfig.InstanceConfig(
                    List.of(tempDir.resolve("does-not-exist").toString()),
                    Map.of(),
                    "127.0.0.1",
                    1883)));

    try (InstanceManager manager = new InstanceManager(config)) {
      assertThrows(java.io.IOException.class, () -> manager.start("broken"));
    }
  }

  private LabConfig config(String... names) {
    Map<String, LabConfig.InstanceConfig> instances = new java.util.LinkedHashMap<>();
    for (String name : names) {
      instances.put(
          name,
          new LabConfig.InstanceConfig(
              List.of(
                  javaBinary(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  TestSleeper.class.getName()),
              Map.of(),
              "127.0.0.1",
              1883));
    }

    return new LabConfig(
        tempDir, "127.0.0.1", 8091, "mosquitto_pub", "mosquitto_sub", instances);
  }

  private String javaBinary() {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
        .toString();
  }
}
