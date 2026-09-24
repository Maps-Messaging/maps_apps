package io.mapsmessaging.tools.testlab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class InstanceManager implements AutoCloseable {

  private static final int MAX_CONFIG_BYTES = 5 * 1024 * 1024;
  private static final int MAX_LOG_READ_BYTES = 1024 * 1024;
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
    return config.instances().keySet().stream().sorted().map(this::status).toList();
  }

  public synchronized Map<String, Object> start(String name) throws IOException {
    LabConfig.InstanceConfig instance = config.requireInstance(name);
    return instance.provider().equals("docker")
        ? startDocker(name, instance)
        : startProcess(name, instance);
  }

  public synchronized Map<String, Object> stop(String name) {
    LabConfig.InstanceConfig instance = config.requireInstance(name);
    if (instance.provider().equals("docker")) {
      try {
        runCommand(
            List.of(config.dockerCommand(), "stop", "-t", "10", dockerContainerName(name)),
            Duration.ofSeconds(20));
      } catch (IOException ignored) {
      }
      return status(name);
    }

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

  public synchronized Map<String, Object> delete(String name) throws IOException {
    LabConfig.InstanceConfig instance = config.requireInstance(name);
    stop(name);

    if (instance.provider().equals("docker")) {
      CommandResult remove =
          runCommand(
              List.of(config.dockerCommand(), "rm", "-f", dockerContainerName(name)),
              Duration.ofSeconds(15));
      if (remove.exitCode() != 0 && !remove.output().contains("No such container")) {
        throw new IOException(
            "Unable to remove Docker container for " + name + ": " + remove.output());
      }
    } else {
      processes.remove(name);
    }

    Path instanceDir = paths(name).instanceDir();
    if (instance.provider().equals("docker") && Files.exists(instanceDir)) {
      CommandResult cleanup =
          runCommand(
              DockerCommandBuilder.workspaceCleanupCommand(
                  config.dockerCommand(), instance.image(), instanceDir),
              Duration.ofSeconds(30));
      if (cleanup.exitCode() != 0) {
        throw new IOException(
            "Unable to clean Docker instance workspace for "
                + name
                + ": "
                + cleanup.output());
      }
    }
    deleteTree(instanceDir);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    result.put("provider", instance.provider());
    result.put("deleted", true);
    result.put("instanceDir", instanceDir.toString());
    if (instance.provider().equals("docker")) {
      result.put("container", dockerContainerName(name));
      result.put("image", instance.image());
      result.put("imageDeleted", false);
    }
    return result;
  }

  public Map<String, Object> status(String name) {
    LabConfig.InstanceConfig instance = config.requireInstance(name);
    Paths paths = paths(name);
    boolean running;
    Long pid = null;

    if (instance.provider().equals("docker")) {
      try {
        CommandResult result =
            runCommand(
                List.of(
                    config.dockerCommand(),
                    "inspect",
                    "-f",
                    "{{.State.Running}} {{.State.Pid}}",
                    dockerContainerName(name)),
                Duration.ofSeconds(10));
        if (result.exitCode() == 0 && !result.output().isBlank()) {
          String[] values = result.output().trim().split("\\s+");
          running = Boolean.parseBoolean(values[0]);
          if (running && values.length > 1) {
            pid = Long.parseLong(values[1]);
          }
        } else {
          running = false;
        }
      } catch (Exception exception) {
        running = false;
      }
    } else {
      Process process = processes.get(name);
      running = process != null && process.isAlive();
      pid = running ? process.pid() : null;
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", name);
    result.put("provider", instance.provider());
    result.put("running", running);
    result.put("pid", pid);
    result.put("instanceDir", paths.instanceDir().toString());
    result.put("configDir", paths.configDir().toString());
    result.put("dataDir", paths.dataDir().toString());
    result.put("logsDir", paths.logsDir().toString());
    result.put("mqttHost", instance.mqttHost());
    result.put("mqttPort", instance.mqttPort());

    if (instance.provider().equals("docker")) {
      result.put("container", dockerContainerName(name));
      result.put("image", instance.image());
      result.put("network", instance.network());
      result.put("debugPort", instance.debugPort() > 0 ? instance.debugPort() : null);
    }
    return result;
  }

  public String logs(String name, int requestedLines) throws IOException {
    LabConfig.InstanceConfig instance = config.requireInstance(name);
    int lines = requestedLines <= 0 ? 200 : Math.min(requestedLines, 5000);

    if (instance.provider().equals("docker")) {
      CommandResult result =
          runCommand(
              List.of(
                  config.dockerCommand(),
                  "logs",
                  "--tail",
                  Integer.toString(lines),
                  dockerContainerName(name)),
              Duration.ofSeconds(15));
      if (result.exitCode() != 0) {
        throw new IOException(result.output());
      }
      return result.output();
    }

    return tail(paths(name).logFile(), lines);
  }

  public List<Map<String, Object>> listLogFiles(String name) throws IOException {
    config.requireInstance(name);
    refreshDockerLog(name);
    Path logsDir = paths(name).logsDir();
    if (!Files.exists(logsDir)) {
      return List.of();
    }

    try (var stream = Files.walk(logsDir)) {
      return stream
          .filter(Files::isRegularFile)
          .sorted()
          .map(
              file -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", logsDir.relativize(file).toString());
                try {
                  item.put("size", Files.size(file));
                  item.put("modified", Files.getLastModifiedTime(file).toInstant().toString());
                } catch (IOException exception) {
                  item.put("size", -1L);
                }
                return item;
              })
          .toList();
    }
  }

  public Map<String, Object> readLog(
      String name, String relativePath, long offset, int requestedBytes) throws IOException {
    config.requireInstance(name);
    refreshDockerLog(name);
    Path logsDir = paths(name).logsDir().toAbsolutePath().normalize();
    Path target = confinedPath(logsDir, relativePath, "Log");

    if (!Files.isRegularFile(target)) {
      throw new IllegalArgumentException("Log file does not exist: " + relativePath);
    }

    long size = Files.size(target);
    long start = Math.max(0L, Math.min(offset, size));
    int length =
        Math.max(
            1,
            Math.min(
                requestedBytes <= 0 ? 64 * 1024 : requestedBytes,
                MAX_LOG_READ_BYTES));
    int available = (int) Math.min(length, size - start);
    byte[] bytes = new byte[available];

    try (var input = Files.newInputStream(target)) {
      input.skipNBytes(start);
      int read = input.readNBytes(bytes, 0, available);
      if (read != available) {
        bytes = java.util.Arrays.copyOf(bytes, read);
      }
    }

    long next = start + bytes.length;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("path", relativePath);
    result.put("offset", start);
    result.put("nextOffset", next);
    result.put("size", size);
    result.put("eof", next >= size);
    result.put("text", new String(bytes, StandardCharsets.UTF_8));
    return result;
  }

  public String readConfig(String name, String relativePath) throws IOException {
    config.requireInstance(name);
    Path configDir = paths(name).configDir().toAbsolutePath().normalize();
    String requested = relativePath == null || relativePath.isBlank() ? "." : relativePath;
    Path target = confinedPath(configDir, requested, "Configuration");

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
      throw new IllegalArgumentException("Configuration file exceeds 5 MiB limit");
    }
    return Files.readString(target, StandardCharsets.UTF_8);
  }

  public Map<String, Object> writeConfig(
      String name,
      String relativePath,
      String content,
      String encoding,
      boolean overwrite)
      throws IOException {
    config.requireInstance(name);
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException("Configuration path is required");
    }

    Path configDir = preparePaths(name).configDir().toAbsolutePath().normalize();
    Path target = confinedPath(configDir, relativePath, "Configuration");
    byte[] bytes =
        "base64".equalsIgnoreCase(encoding)
            ? Base64.getDecoder().decode(content == null ? "" : content)
            : (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);

    if (bytes.length > MAX_CONFIG_BYTES) {
      throw new IllegalArgumentException("Configuration content exceeds 5 MiB limit");
    }
    if (Files.exists(target) && !overwrite) {
      throw new IllegalStateException("Configuration file already exists: " + relativePath);
    }

    Files.createDirectories(target.getParent());
    Files.write(target, bytes);

    return Map.of(
        "path", configDir.relativize(target).toString(),
        "size", bytes.length,
        "overwritten", overwrite);
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
    return commandResult(runCommand(command, Duration.ofSeconds(15)));
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
    return commandResult(runCommand(command, Duration.ofSeconds(timeout + 5L)));
  }

  public Path createEvidence(String name, String scenario) throws IOException {
    config.requireInstance(name);
    refreshDockerLog(name);
    String safeScenario = safeScenario(scenario);
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
        destination.resolve("manifest.json"), gson.toJson(manifest), StandardCharsets.UTF_8);
    return destination;
  }

  public Map<String, Object> createEvidenceArchive(String name, String scenario) throws IOException {
    Path evidence = createEvidence(name, scenario);
    Path zip = evidence.resolveSibling(evidence.getFileName() + ".zip");
    zipDirectory(evidence, zip);
    return Map.of("path", zip.toString(), "size", Files.size(zip));
  }

  @Override
  public void close() {
    for (String name : new ArrayList<>(config.instances().keySet())) {
      stop(name);
    }
  }

  private Map<String, Object> startProcess(
      String name, LabConfig.InstanceConfig instance) throws IOException {
    Process current = processes.get(name);
    if (current != null && current.isAlive()) {
      throw new IllegalStateException("Instance already running: " + name);
    }

    Paths paths = preparePaths(name);
    List<String> command =
        instance.command().stream().map(value -> expand(value, name, paths)).toList();

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(paths.instanceDir().toFile());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logFile().toFile()));
    builder.environment().putAll(instance.environment());
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

  private Map<String, Object> startDocker(
      String name, LabConfig.InstanceConfig instance) throws IOException {
    Paths paths = preparePaths(name);
    String containerName = dockerContainerName(name);

    CommandResult existing =
        runCommand(
            List.of(
                config.dockerCommand(),
                "ps",
                "-a",
                "--filter",
                "name=^/" + containerName + "$",
                "--format",
                "{{.ID}}"),
            Duration.ofSeconds(10));

    if (!existing.output().isBlank()) {
      if (Boolean.TRUE.equals(status(name).get("running"))) {
        throw new IllegalStateException("Instance already running: " + name);
      }
      runCommand(
          List.of(config.dockerCommand(), "rm", "-f", containerName), Duration.ofSeconds(15));
    }

    ensureDockerConfigSeeded(name, instance, paths.configDir());
    ensureDockerDataOwnership(instance, paths.dataDir());
    ensureDockerNetwork(instance.network());

    boolean appendContainerCommand =
        instance.containerCommand().isEmpty() || dockerImageHasEntrypoint(instance.image());

    List<String> command =
        DockerCommandBuilder.runCommand(
            config.dockerCommand(),
            containerName,
            instance,
            paths.configDir(),
            paths.dataDir(),
            appendContainerCommand);

    CommandResult result = runCommand(command, Duration.ofSeconds(60));
    if (result.exitCode() != 0) {
      throw new IOException("Docker start failed for " + name + ": " + result.output());
    }

    return status(name);
  }

  private void ensureDockerConfigSeeded(
      String name, LabConfig.InstanceConfig instance, Path configDir) throws IOException {
    try (var stream = Files.list(configDir)) {
      if (stream.findAny().isPresent()) {
        return;
      }
    }

    String seedContainer = dockerContainerName(name) + "-config-seed";
    runCommand(
        List.of(config.dockerCommand(), "rm", "-f", seedContainer),
        Duration.ofSeconds(10));

    CommandResult create =
        runCommand(
            DockerCommandBuilder.seedCreateCommand(
                config.dockerCommand(), seedContainer, instance.image()),
            Duration.ofSeconds(30));
    if (create.exitCode() != 0) {
      throw new IOException(
          "Unable to create configuration seed container for "
              + name
              + ": "
              + create.output());
    }

    try {
      CommandResult copy =
          runCommand(
              DockerCommandBuilder.seedCopyCommand(
                  config.dockerCommand(), seedContainer, configDir),
              Duration.ofSeconds(30));
      if (copy.exitCode() != 0) {
        throw new IOException(
            "Unable to seed configuration for " + name + ": " + copy.output());
      }

      try (var stream = Files.list(configDir)) {
        if (stream.findAny().isEmpty()) {
          throw new IOException(
              "Docker image "
                  + instance.image()
                  + " did not provide configuration under /opt/maps/conf");
        }
      }
    } finally {
      runCommand(
          List.of(config.dockerCommand(), "rm", "-f", seedContainer),
          Duration.ofSeconds(10));
    }
  }

  private void ensureDockerDataOwnership(
      LabConfig.InstanceConfig instance, Path dataDir) throws IOException {
    CommandResult ownership =
        runCommand(
            DockerCommandBuilder.dataOwnershipCommand(
                config.dockerCommand(), instance.image(), dataDir),
            Duration.ofSeconds(30));
    if (ownership.exitCode() != 0) {
      throw new IOException(
          "Unable to set Maps data directory ownership for image "
              + instance.image()
              + ": "
              + ownership.output());
    }
  }

  private boolean dockerImageHasEntrypoint(String image) throws IOException {
    CommandResult inspect =
        runCommand(
            List.of(
                config.dockerCommand(),
                "image",
                "inspect",
                "-f",
                "{{json .Config.Entrypoint}}",
                image),
            Duration.ofSeconds(10));
    if (inspect.exitCode() != 0) {
      throw new IOException("Unable to inspect Docker image " + image + ": " + inspect.output());
    }

    String entrypoint = inspect.output().trim();
    return !entrypoint.isBlank() && !entrypoint.equals("null") && !entrypoint.equals("[]");
  }

  private void ensureDockerNetwork(String network) throws IOException {
    if (network == null || network.isBlank()) {
      return;
    }

    CommandResult inspect =
        runCommand(
            List.of(config.dockerCommand(), "network", "inspect", network),
            Duration.ofSeconds(10));
    if (inspect.exitCode() == 0) {
      return;
    }

    CommandResult create =
        runCommand(
            List.of(config.dockerCommand(), "network", "create", network),
            Duration.ofSeconds(15));
    if (create.exitCode() != 0) {
      throw new IOException("Unable to create Docker network " + network + ": " + create.output());
    }
  }

  private void refreshDockerLog(String name) throws IOException {
    LabConfig.InstanceConfig instance = config.requireInstance(name);
    if (!instance.provider().equals("docker")) {
      return;
    }

    Paths paths = preparePaths(name);
    CommandResult result =
        runCommand(
            List.of(config.dockerCommand(), "logs", dockerContainerName(name)),
            Duration.ofSeconds(30));
    if (result.exitCode() == 0) {
      Files.writeString(
          paths.logsDir().resolve("docker.log"), result.output(), StandardCharsets.UTF_8);
    }
  }

  private String tail(Path file, int lines) throws IOException {
    if (!Files.exists(file)) {
      return "";
    }

    ArrayDeque<String> tail = new ArrayDeque<>(lines);
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
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

  private Path confinedPath(Path root, String relativePath, String label) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new IllegalArgumentException(label + " path is required");
    }

    Path target = root.resolve(relativePath).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException(label + " path escapes instance directory");
    }
    return target;
  }

  private String expand(String value, String name, Paths paths) {
    return value
        .replace("${instance}", name)
        .replace("${instanceDir}", paths.instanceDir().toString())
        .replace("${configDir}", paths.configDir().toString())
        .replace("${dataDir}", paths.dataDir().toString())
        .replace("${logFile}", paths.logFile().toString());
  }

  private String dockerContainerName(String name) {
    return "maps-test-lab-" + name;
  }

  private String safeScenario(String scenario) {
    return scenario == null || scenario.isBlank()
        ? "manual"
        : scenario.replaceAll("[^A-Za-z0-9._-]", "_");
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

  private void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }

    try (var stream = Files.walk(root)) {
      for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
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

  private void zipDirectory(Path source, Path zip) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip));
        var stream = Files.walk(source)) {
      for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
        String entryName = source.getFileName() + "/" + source.relativize(file);
        output.putNextEntry(new ZipEntry(entryName.replace('\\', '/')));
        Files.copy(file, output);
        output.closeEntry();
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
