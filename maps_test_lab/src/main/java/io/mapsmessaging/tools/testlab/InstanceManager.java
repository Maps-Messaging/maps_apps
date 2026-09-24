package io.mapsmessaging.tools.testlab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class InstanceManager implements AutoCloseable {

  private static final int MAX_CONFIG_BYTES = 1024 * 1024;
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
  private static final DateTimeFormatter EVIDENCE_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private final LabConfig config;
  private final Map<String, Process> processes = new ConcurrentHashMap<>();
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

  public InstanceManager(LabConfig config) throws IOException {
    this.config = config;
    Files.createDirectories(config.root().resolve("instances"));
    Files.createDirectories(config.root().resolve("evidence"));
  }

  public List<Map<String, Object>> listInstances() {
    return config.instances().keySet().stream()
        .sorted()
        .map(this::status)
        .toList();
  }

  public synchronized Map<String, Object> start(String name) throws IOException {
    LabConfig.InstanceConfig instanceConfig = config.requireInstance(name);
    Process current = processes.get(name);
    if (current != null && current.isAlive()) {
      throw new IllegalStateException("Instance already running: " + name);
    }

    Paths paths = preparePaths(name);
    List<String> command =
        instanceConfig.command().stream().map(value -> expand(value, name, paths)).toList();

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(paths.instanceDir().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logFile().toFile()));
    builder.environment().putAll(instanceConfig.environment());
    builder.environment().put("MAPS_TEST_LAB_INSTANCE", name);
    builder.environment().put("MAPS_TEST_LAB_INSTANCE_DIR", paths.instanceDir().toString());
    builder.environment().put("MAPS_TEST_LAB_CONFIG_DIR", paths.configDir().toString());
    builder.environment().put("MAPS_TEST_LAB_DATA_DIR", paths.dataDir().toString());

    Process process = builder.start();
    processes.put(name, process);

    try {
      if (process.waitFor(250, TimeUnit.MILLISECONDS)) {
        processes.remove(name);
        throw new IOException(
            "Instance " + name + " exited during startup with code " + process.exitValue());
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      processes.remove(name);
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while starting instance " + name, exception);
    }

    return status(name);
  }

  public synchronized Map<String, Object> stop(String name) {
    config.requireInstance(name);
    Process process = processes.get(name);
    if (process == null || !process.isAlive()) {
      processes.remove(name);
      return status(name);
    }

    process.destroy();
    try {
      if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }

    processes.remove(name);
    return status(name);
  }

  public synchronized Map<String, Object> restart(String name) throws IOException {
    stop(name);
    return start(name);
  }

  public Map<String, Object> status(String name) {
    LabConfig.InstanceConfig instanceConfig = config.requireInstance(name);
    Process process = processes.get(name);
    boolean running = process != null && process.isAlive();
    Paths paths = paths(name);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    result.put("running", running);
    result.put("pid", running ? process.pid() : null);
    result.put("instanceDir", paths.instanceDir().toString());
    result.put("configDir", paths.configDir().toString());
    result.put("dataDir", paths.dataDir().toString());
    result.put("logFile", paths.logFile().toString());
    result.put("mqttHost", instanceConfig.mqttHost());
    result.put("mqttPort", instanceConfig.mqttPort());
    return result;
  }

  public String logs(String name, int requestedLines) throws IOException {
    config.requireInstance(name);
    int lines = requestedLines <= 0 ? 200 : Math.min(requestedLines, 5000);
    Path logFile = paths(name).logFile();
    if (!Files.exists(logFile)) {
      return "";
    }

    ArrayDeque<String> tail = new ArrayDeque<>(lines);
    try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (tail.size() == lines) {
          tail.removeFirst();
        }
        tail.addLast(line);
      }
    }
    return String.join(System.lineSeparator(), tail);
  }

  public String readConfig(String name, String relativePath) throws IOException {
    config.requireInstance(name);
    Path configDir = paths(name).configDir().toAbsolutePath().normalize();
    String requested = relativePath == null || relativePath.isBlank() ? "." : relativePath;
    Path target = configDir.resolve(requested).normalize();

    if (!target.startsWith(configDir)) {
      throw new IllegalArgumentException("Configuration path escapes instance config directory");
    }
    if (!Files.exists(target)) {
      throw new IllegalArgumentException("Configuration path does not exist: " + requested);
    }

    if (Files.isDirectory(target)) {
      try (var stream = Files.walk(target)) {
        return stream
            .filter(Files::isRegularFile)
            .map(configDir::relativize)
            .map(Path::toString)
            .sorted()
            .reduce((left, right) -> left + System.lineSeparator() + right)
            .orElse("");
      }
    }

    if (Files.size(target) > MAX_CONFIG_BYTES) {
      throw new IllegalArgumentException("Configuration file exceeds 1 MiB limit");
    }
    return Files.readString(target, StandardCharsets.UTF_8);
  }

  public Map<String, Object> mqttPublish(
      String name, String topic, String payload, int qos, boolean retain) throws IOException {
    LabConfig.InstanceConfig instance = config.requireInstance(name);
    requireTopic(topic);
    int checkedQos = requireQos(qos);

    List<String> command = new ArrayList<>();
    command.add(config.mqttPubCommand());
    command.addAll(
        List.of(
            "-h",
            instance.mqttHost(),
            "-p",
            Integer.toString(instance.mqttPort()),
            "-t",
            topic,
            "-m",
            payload == null ? "" : payload,
            "-q",
            Integer.toString(checkedQos)));
    if (retain) {
      command.add("-r");
    }

    CommandResult result = runCommand(command, Duration.ofSeconds(15));
    return commandResult(result);
  }

  public Map<String, Object> mqttSubscribe(
      String name, String topic, int qos, int timeoutSeconds) throws IOException {
    LabConfig.InstanceConfig instance = config.requireInstance(name);
    requireTopic(topic);
    int checkedQos = requireQos(qos);
    int timeout = timeoutSeconds <= 0 ? 10 : Math.min(timeoutSeconds, 120);

    List<String> command =
        List.of(
            config.mqttSubCommand(),
            "-h",
            instance.mqttHost(),
            "-p",
            Integer.toString(instance.mqttPort()),
            "-t",
            topic,
            "-q",
            Integer.toString(checkedQos),
            "-C",
            "1",
            "-W",
            Integer.toString(timeout));

    CommandResult result = runCommand(command, Duration.ofSeconds(timeout + 5L));
    return commandResult(result);
  }

  public Path createEvidence(String name, String scenario) throws IOException {
    config.requireInstance(name);
    String safeScenario =
        scenario == null || scenario.isBlank()
            ? "manual"
            : scenario.replaceAll("[^A-Za-z0-9._-]", "_");
    Path destination =
        config
            .root()
            .resolve("evidence")
            .resolve(EVIDENCE_TIME.format(Instant.now()) + "-" + safeScenario + "-" + name);
    Files.createDirectories(destination);

    Paths source = paths(name);
    copyTree(source.configDir(), destination.resolve("config"));
    copyTree(source.logsDir(), destination.resolve("logs"));

    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("created", Instant.now().toString());
    manifest.put("scenario", safeScenario);
    manifest.put("instance", status(name));
    Files.writeString(
        destination.resolve("manifest.json"),
        gson.toJson(manifest),
        StandardCharsets.UTF_8);

    return destination;
  }

  @Override
  public void close() {
    for (String name : new ArrayList<>(processes.keySet())) {
      stop(name);
    }
  }

  private Paths preparePaths(String name) throws IOException {
    Paths paths = paths(name);
    Files.createDirectories(paths.configDir());
    Files.createDirectories(paths.dataDir());
    Files.createDirectories(paths.logsDir());
    return paths;
  }

  private Paths paths(String name) {
    LabConfig.validateInstanceName(name);
    Path instanceDir = config.root().resolve("instances").resolve(name).toAbsolutePath().normalize();
    Path expectedRoot = config.root().resolve("instances").toAbsolutePath().normalize();
    if (!instanceDir.startsWith(expectedRoot)) {
      throw new IllegalArgumentException("Instance path escapes test lab root");
    }
    Path logs = instanceDir.resolve("logs");
    return new Paths(
        instanceDir,
        instanceDir.resolve("config"),
        instanceDir.resolve("data"),
        logs,
        logs.resolve("maps.log"));
  }

  private String expand(String value, String name, Paths paths) {
    return value
        .replace("${instance}", name)
        .replace("${instanceDir}", paths.instanceDir().toString())
        .replace("${configDir}", paths.configDir().toString())
        .replace("${dataDir}", paths.dataDir().toString())
        .replace("${logFile}", paths.logFile().toString());
  }

  private CommandResult runCommand(List<String> command, Duration timeout) throws IOException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IOException("Command timed out");
      }
    } catch (InterruptedException exception) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("Command interrupted", exception);
    }

    String output =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new CommandResult(process.exitValue(), output);
  }

  private Map<String, Object> commandResult(CommandResult result) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("exitCode", result.exitCode());
    response.put("output", result.output());
    return response;
  }

  private void copyTree(Path source, Path destination) throws IOException {
    if (!Files.exists(source)) {
      return;
    }
    try (var stream = Files.walk(source)) {
      for (Path path : stream.sorted(Comparator.naturalOrder()).toList()) {
        Path relative = source.relativize(path);
        Path target = destination.resolve(relative);
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private void requireTopic(String topic) {
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("MQTT topic is required");
    }
  }

  private int requireQos(int qos) {
    if (qos < 0 || qos > 2) {
      throw new IllegalArgumentException("QoS must be 0, 1 or 2");
    }
    return qos;
  }

  private record Paths(
      Path instanceDir, Path configDir, Path dataDir, Path logsDir, Path logFile) {}

  private record CommandResult(int exitCode, String output) {}
}
