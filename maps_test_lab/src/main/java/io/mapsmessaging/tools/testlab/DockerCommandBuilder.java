package io.mapsmessaging.tools.testlab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DockerCommandBuilder {

  private DockerCommandBuilder() {}

  static List<String> runCommand(
      String dockerCommand,
      String containerName,
      LabConfig.InstanceConfig instance,
      Path configDir,
      Path dataDir) {
    List<String> command = new ArrayList<>();
    command.add(dockerCommand);
    command.addAll(List.of("run", "-d", "--name", containerName));

    if (!instance.network().isBlank()) {
      command.add("--network");
      command.add(instance.network());
    }

    command.add("-v");
    command.add(configDir + ":/opt/maps/config");
    command.add("-v");
    command.add(dataDir + ":/opt/maps_data");

    for (String port : instance.ports()) {
      command.add("-p");
      command.add(port);
    }

    Map<String, String> environment = new LinkedHashMap<>(instance.environment());
    if (instance.debugPort() > 0) {
      command.add("-p");
      command.add(instance.debugPort() + ":" + instance.debugPort());

      String jdwp =
          "-agentlib:jdwp=transport=dt_socket,server=y,suspend="
              + (instance.debugSuspend() ? "y" : "n")
              + ",address=*:"
              + instance.debugPort();

      String current = environment.getOrDefault("JAVA_TOOL_OPTIONS", "");
      environment.put("JAVA_TOOL_OPTIONS", (current + " " + jdwp).trim());
    }

    environment.forEach(
        (key, value) -> {
          command.add("-e");
          command.add(key + "=" + value);
        });

    command.add(instance.image());
    command.addAll(instance.containerCommand());
    return List.copyOf(command);
  }
}
