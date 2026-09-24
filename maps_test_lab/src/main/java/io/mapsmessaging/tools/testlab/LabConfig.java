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
    String mqttPubCommand,
    String mqttSubCommand,
    Map<String, InstanceConfig> instances) {

  private static final Pattern INSTANCE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  public LabConfig {
    Objects.requireNonNull(root, "root");
    bindAddress = bindAddress == null || bindAddress.isBlank() ? "127.0.0.1" : bindAddress;
    port = port <= 0 ? 8091 : port;
    mqttPubCommand =
        mqttPubCommand == null || mqttPubCommand.isBlank() ? "mosquitto_pub" : mqttPubCommand;
    mqttSubCommand =
        mqttSubCommand == null || mqttSubCommand.isBlank() ? "mosquitto_sub" : mqttSubCommand;
    instances = instances == null ? Map.of() : Map.copyOf(instances);

    for (Map.Entry<String, InstanceConfig> entry : instances.entrySet()) {
      validateInstanceName(entry.getKey());
      if (entry.getValue() == null
          || entry.getValue().command() == null
          || entry.getValue().command().isEmpty()) {
        throw new IllegalArgumentException("Instance " + entry.getKey() + " has no command");
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
                        instance.command,
                        instance.environment == null ? Map.of() : instance.environment,
                        instance.mqttHost,
                        instance.mqttPort)));
      }

      return new LabConfig(
          root.toAbsolutePath().normalize(),
          raw.bindAddress,
          raw.port,
          raw.mqttPubCommand,
          raw.mqttSubCommand,
          instanceConfigs);
    }
  }

  public InstanceConfig requireInstance(String name) {
    validateInstanceName(name);
    InstanceConfig config = instances.get(name);
    if (config == null) {
      throw new IllegalArgumentException("Unknown instance: " + name);
    }
    return config;
  }

  public static void validateInstanceName(String name) {
    if (name == null || !INSTANCE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid instance name: " + name);
    }
  }

  public record InstanceConfig(
      List<String> command,
      Map<String, String> environment,
      String mqttHost,
      int mqttPort) {

    public InstanceConfig {
      command = command == null ? List.of() : List.copyOf(command);
      environment = environment == null ? Map.of() : Map.copyOf(environment);
      mqttHost = mqttHost == null || mqttHost.isBlank() ? "127.0.0.1" : mqttHost;
      mqttPort = mqttPort <= 0 ? 1883 : mqttPort;
    }
  }

  private static final class RawConfig {
    String root;
    String bindAddress;
    int port;
    String mqttPubCommand;
    String mqttSubCommand;
    Map<String, RawInstance> instances;
  }

  private static final class RawInstance {
    List<String> command;
    Map<String, String> environment;
    String mqttHost;
    int mqttPort;
  }
}
