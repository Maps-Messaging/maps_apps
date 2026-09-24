package io.mapsmessaging.tools.testlab;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DockerCommandBuilder {

  private DockerCommandBuilder() {}

  static List<String> seedCreateCommand(
      String dockerCommand, String containerName, String image) {
    return List.of(dockerCommand, "create", "--name", containerName, image);
  }

  static List<String> seedCopyCommand(
      String dockerCommand, String containerName, Path configDir) {
    return List.of(
        dockerCommand,
        "cp",
        containerName + ":/opt/maps/conf/.",
        configDir.toString());
  }

  static List<String> runCommand(
      String dockerCommand,
      String containerName,
      LabConfig.InstanceConfig instance,
      Path configDir,
      Path dataDir,
      boolean appendContainerCommand) {
    List<String> command = new ArrayList<>();
    command.add(dockerCommand);
    command.addAll(List.of("run", "-d", "--name", containerName));

    if (!instance.network().isBlank()) {
      command.add("--network");
      command.add(instance.network());
    }

    command.add("-v");
    command.add(configDir + ":/opt/maps/conf");
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
    if (appendContainerCommand) {
      command.addAll(instance.containerCommand());
    }
    return List.copyOf(command);
  }
}
