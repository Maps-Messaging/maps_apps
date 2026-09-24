package io.mapsmessaging.tools.testlab;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record LabConfig(
    Path root,
    String bindAddress,
    int port,
    String dockerCommand,
    String mqttPubCommand,
    String mqttSubCommand,
    Map<String, InstanceConfig> instances) {

  private static final Pattern INSTANCE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  public LabConfig {
    Objects.requireNonNull(root, "root");
    bindAddress = blankDefault(bindAddress, "127.0.0.1");
    port = port <= 0 ? 8091 : port;
    dockerCommand = blankDefault(dockerCommand, "docker");
    mqttPubCommand = blankDefault(mqttPubCommand, "mosquitto_pub");
    mqttSubCommand = blankDefault(mqttSubCommand, "mosquitto_sub");
    instances = instances == null ? Map.of() : Map.copyOf(instances);

    for (Map.Entry<String, InstanceConfig> entry : instances.entrySet()) {
      validateInstanceName(entry.getKey());
      InstanceConfig instance = Objects.requireNonNull(entry.getValue(), "instance " + entry.getKey());
      if ("docker".equals(instance.provider())) {
        if (instance.image() == null || instance.image().isBlank()) {
          throw new IllegalArgumentException("Docker instance " + entry.getKey() + " has no image");
        }
      } else if (instance.command().isEmpty()) {
        throw new IllegalArgumentException("Process instance " + entry.getKey() + " has no command");
      }
    }
  }

  public static LabConfig load(Path configFile) throws IOException {
    Path absolute = configFile.toAbsolutePath().normalize();
    try (Reader reader = Files.newBufferedReader(absolute)) {
      RawConfig raw = new Gson().fromJson(reader, RawConfig.class);
      if (raw == null) {
        throw new IllegalArgumentException("Empty lab configuration");
      }

      Path base = absolute.getParent();
      Path root =
          raw.root == null || raw.root.isBlank()
              ? base.resolve("maps-test-lab")
              : Path.of(raw.root);
      if (!root.isAbsolute()) {
        root = base.resolve(root);
      }

      Map<String, InstanceConfig> instanceConfigs = new LinkedHashMap<>();
      if (raw.instances != null) {
        raw.instances.forEach(
            (name, instance) ->
                instanceConfigs.put(
                    name,
                    new InstanceConfig(
                        instance.provider,
                        instance.command,
                        instance.environment,
                        instance.mqttHost,
                        instance.mqttPort,
                        instance.image,
                        instance.network,
                        instance.containerCommand,
                        instance.ports,
                        instance.debugPort,
                        instance.debugSuspend)));
      }

      return new LabConfig(
          root.toAbsolutePath().normalize(),
          raw.bindAddress,
          raw.port,
          raw.dockerCommand,
          raw.mqttPubCommand,
          raw.mqttSubCommand,
          instanceConfigs);
    }
  }

  public InstanceConfig requireInstance(String name) {
    validateInstanceName(name);
    InstanceConfig instance = instances.get(name);
    if (instance == null) {
      throw new IllegalArgumentException("Unknown instance: " + name);
    }
    return instance;
  }

  public static void validateInstanceName(String name) {
    if (name == null || !INSTANCE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid instance name: " + name);
    }
  }

  private static String blankDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  public record InstanceConfig(
      String provider,
      List<String> command,
      Map<String, String> environment,
      String mqttHost,
      int mqttPort,
      String image,
      String network,
      List<String> containerCommand,
      List<String> ports,
      int debugPort,
      boolean debugSuspend) {

    public InstanceConfig {
      provider = blankDefault(provider, "process").toLowerCase();
      if (!provider.equals("process") && !provider.equals("docker")) {
        throw new IllegalArgumentException("Unsupported instance provider: " + provider);
      }
      command = command == null ? List.of() : List.copyOf(command);
      environment = environment == null ? Map.of() : Map.copyOf(environment);
      mqttHost = blankDefault(mqttHost, "127.0.0.1");
      mqttPort = mqttPort <= 0 ? 1883 : mqttPort;
      image = image == null ? "" : image;
      network = network == null ? "" : network;
      containerCommand = containerCommand == null ? List.of() : List.copyOf(containerCommand);
      ports = ports == null ? List.of() : List.copyOf(ports);
      debugPort = debugPort < 0 ? 0 : debugPort;
    }

    public InstanceConfig(
        List<String> command,
        Map<String, String> environment,
        String mqttHost,
        int mqttPort) {
      this("process", command, environment, mqttHost, mqttPort, "", "", List.of(), List.of(), 0, false);
    }

    public static InstanceConfig process(
        List<String> command, Map<String, String> environment, String mqttHost, int mqttPort) {
      return new InstanceConfig(
          "process", command, environment, mqttHost, mqttPort, "", "", List.of(), List.of(), 0, false);
    }

    public static InstanceConfig docker(
        String image,
        String network,
        List<String> containerCommand,
        List<String> ports,
        Map<String, String> environment,
        String mqttHost,
        int mqttPort,
        int debugPort,
        boolean debugSuspend) {
      return new InstanceConfig(
          "docker",
          List.of(),
          environment,
          mqttHost,
          mqttPort,
          image,
          network,
          containerCommand,
          ports,
          debugPort,
          debugSuspend);
    }
  }

  private static final class RawConfig {
    String root;
    String bindAddress;
    int port;
    String dockerCommand;
    String mqttPubCommand;
    String mqttSubCommand;
    Map<String, RawInstance> instances;
  }

  private static final class RawInstance {
    String provider;
    List<String> command;
    Map<String, String> environment;
    String mqttHost;
    int mqttPort;
    String image;
    String network;
    List<String> containerCommand;
    List<String> ports;
    int debugPort;
    boolean debugSuspend;
  }
}
