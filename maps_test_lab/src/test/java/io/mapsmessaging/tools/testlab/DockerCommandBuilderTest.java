package io.mapsmessaging.tools.testlab;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerCommandBuilderTest {

  @Test
  void buildsDockerRunCommandWithNetworkPortsAndJdwp() {
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
            Path.of("/srv/maps-test-lab/instances/ralf-a/data"));

    assertTrue(command.contains("mapsmessaging/server:test"));
    assertTrue(command.contains("ralf-lab"));
    assertTrue(command.contains("18831:1883"));
    assertTrue(command.contains("5005:5005"));
    assertTrue(
        command.stream()
            .anyMatch(
                value ->
                    value.contains("JAVA_TOOL_OPTIONS=")
                        && value.contains("jdwp")
                        && value.contains("suspend=y")
                        && value.contains("address=*:5005")));
  }
}
