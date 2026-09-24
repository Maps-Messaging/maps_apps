package io.mapsmessaging.tools.testlab;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerCommandBuilderTest {

  @Test
  void buildsDockerRunCommandWithNetworkPortsJdwpAndEntrypointArguments() {
    LabConfig.InstanceConfig instance =
        LabConfig.InstanceConfig.docker(
            "mapsmessaging/server:test",
            "ralf-lab",
            List.of("--config", "/opt/maps/config"),
            List.of("18831:1883"),
            Map.of("MAPS_ENV", "test"),
            "127.0.0.1",
            18831,
            5005,
            true);

    List<String> command =
        DockerCommandBuilder.runCommand(
            "docker",
            "maps-test-lab-ralf-a",
            instance,
            Path.of("/srv/maps-test-lab/instances/ralf-a/config"),
            Path.of("/srv/maps-test-lab/instances/ralf-a/data"),
            true);

    assertTrue(command.contains("mapsmessaging/server:test"));
    assertTrue(command.contains("ralf-lab"));
    assertTrue(command.contains("18831:1883"));
    assertTrue(command.contains("5005:5005"));
    assertTrue(command.contains("--config"));
    assertTrue(command.contains("/opt/maps/config"));
    assertTrue(
        command.contains(
            "/srv/maps-test-lab/instances/ralf-a/config:/opt/maps/conf"));
    assertTrue(
        command.stream()
            .anyMatch(
                value ->
                    value.contains("JAVA_TOOL_OPTIONS=")
                        && value.contains("jdwp")
                        && value.contains("suspend=y")
                        && value.contains("address=*:5005")));
  }

  @Test
  void preservesImageCmdWhenImageHasNoEntrypoint() {
    LabConfig.InstanceConfig instance =
        LabConfig.InstanceConfig.docker(
            "mapsmessaging/server_daemon_local:latest",
            "ralf-lab",
            List.of("--config", "/opt/maps/config"),
            List.of("18831:1883"),
            Map.of(),
            "127.0.0.1",
            18831,
            0,
            false);

    List<String> command =
        DockerCommandBuilder.runCommand(
            "docker",
            "maps-test-lab-maps-a",
            instance,
            Path.of("/srv/maps-test-lab/instances/maps-a/config"),
            Path.of("/srv/maps-test-lab/instances/maps-a/data"),
            false);

    assertTrue(command.contains("mapsmessaging/server_daemon_local:latest"));
    assertFalse(command.contains("--config"));
    assertFalse(command.contains("/opt/maps/config"));
    assertTrue(
        command.contains(
            "/srv/maps-test-lab/instances/maps-a/config:/opt/maps/conf"));
  }

  @Test
  void buildsConfigurationSeedCommandsForMapsConf() {
    List<String> create =
        DockerCommandBuilder.seedCreateCommand(
            "docker",
            "maps-test-lab-maps-a-config-seed",
            "mapsmessaging/server_daemon_local:latest");

    List<String> copy =
        DockerCommandBuilder.seedCopyCommand(
            "docker",
            "maps-test-lab-maps-a-config-seed",
            Path.of("/srv/maps-test-lab/instances/maps-a/config"));

    assertTrue(create.contains("create"));
    assertTrue(create.contains("mapsmessaging/server_daemon_local:latest"));
    assertTrue(copy.contains("maps-test-lab-maps-a-config-seed:/opt/maps/conf/."));
    assertTrue(copy.contains("/srv/maps-test-lab/instances/maps-a/config"));
  }
}
