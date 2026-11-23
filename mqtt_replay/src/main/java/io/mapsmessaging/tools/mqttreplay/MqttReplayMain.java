package io.mapsmessaging.tools.mqttreplay;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.stream.Stream;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public final class MqttReplayMain {

  private MqttReplayMain() {
  }

  public static void main(String[] args) {
    try {
      Options options = Options.parse(args);
      replay(options);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      System.err.println("Replay interrupted");
      System.exit(130);
    } catch (Exception exception) {
      System.err.println("Replay failed: " + exception.getMessage());
      System.exit(1);
    }
  }

  static void replay(Options options) throws Exception {
    long count = 0;
    long started = System.nanoTime();
    Instant first = null;

    try (EventReader reader = new EventReader(options.directory(), options.qos());
         Publisher publisher = new Publisher(options.broker(), options.sessionPerTopic())) {
      Event event;
      while ((event = reader.next()) != null) {
        if (first == null) {
          first = event.timestamp();
        }
        long delta = Duration.between(first, event.timestamp()).toNanos();
        long target = Math.max(0L, Math.round(delta / options.speed()));
        long remaining = target - (System.nanoTime() - started);
        if (remaining > 0) {
          TimeUnit.NANOSECONDS.sleep(remaining);
        }
        publisher.publish(event);
        count++;
        if (count % 10000 == 0) {
          System.out.printf("Published %,d events using %d sessions%n", count, publisher.sessionCount());
        }
      }
      System.out.printf("Completed: %,d events, %d sessions%n", count, publisher.sessionCount());
    }
  }

  record Options(Path directory, String broker, double speed, boolean sessionPerTopic, int qos) {
    static Options parse(String[] arguments) {
      Path directory = null;
      String broker = null;
      double speed = 1.0;
      boolean sessionPerTopic = false;
      int qos = 1;

      for (int i = 0; i < arguments.length; i++) {
        String option = arguments[i];
        switch (option) {
          case "--dir" -> directory = Path.of(value(arguments, ++i, option));
          case "--broker" -> broker = value(arguments, ++i, option);
          case "--speed" -> speed = Double.parseDouble(value(arguments, ++i, option));
          case "--session-per-topic" -> sessionPerTopic = true;
          case "--qos" -> qos = Integer.parseInt(value(arguments, ++i, option));
          default -> throw new IllegalArgumentException("Unknown argument: " + option);
        }
      }
      if (directory == null || !Files.isDirectory(directory)) {
        throw new IllegalArgumentException("--dir must name an existing directory");
      }
      if (broker == null || broker.isBlank()) {
        throw new IllegalArgumentException("--broker is required");
      }
      if (!Double.isFinite(speed) || speed <= 0) {
        throw new IllegalArgumentException("--speed must be finite and positive");
      }
      if (qos < 0 || qos > 2) {
        throw new IllegalArgumentException("--qos must be 0, 1 or 2");
      }
      return new Options(directory, broker, speed, sessionPerTopic, qos);
    }

    private static String value(String[] arguments, int index, String option) {
      if (index >= arguments.length || arguments[index].isBlank()) {
        throw new IllegalArgumentException("Missing value for " + option);
      }
      return arguments[index];
    }
  }

  record Event(Instant timestamp, String topic, byte[] payload, int qos, boolean retained) {
  }

  static Event decode(String line, int defaultQos) {
    JsonObject record = JsonParser.parseString(line).getAsJsonObject();
    if (present(record, "opaqueData")) {
      byte[] bytes = Base64.getDecoder().decode(record.get("opaqueData").getAsString());
      record = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    if (!present(record, "topic") || record.get("topic").getAsString().isBlank()) {
      throw new IllegalArgumentException("Missing topic");
    }
    Instant timestamp;
    if (present(record, "receivedTimestamp")) {
      timestamp = Instant.parse(record.get("receivedTimestamp").getAsString());
    } else if (present(record, "serverTimestamp")) {
      timestamp = Instant.parse(record.get("serverTimestamp").getAsString());
    } else if (present(record, "serverTimeMs")) {
      timestamp = Instant.ofEpochMilli(record.get("serverTimeMs").getAsLong());
    } else if (record.has("meta") && record.get("meta").isJsonObject()
        && present(record.getAsJsonObject("meta"), "time_ms")) {
      timestamp = Instant.ofEpochMilli(record.getAsJsonObject("meta").get("time_ms").getAsLong());
    } else if (present(record, "timestamp")) {
      timestamp = Instant.parse(record.get("timestamp").getAsString());
    } else {
      throw new IllegalArgumentException("Missing timestamp");
    }

    if (!present(record, "payload")) {
      throw new IllegalArgumentException("Missing payload");
    }
    JsonElement payload = record.get("payload");
    byte[] bytes = (payload.isJsonPrimitive() && payload.getAsJsonPrimitive().isString()
        ? payload.getAsString() : payload.toString()).getBytes(StandardCharsets.UTF_8);

    int qos = defaultQos;
    if (present(record, "qos")) {
      qos = switch (record.get("qos").getAsString()) {
        case "AT_MOST_ONCE" -> 0;
        case "AT_LEAST_ONCE" -> 1;
        case "EXACTLY_ONCE" -> 2;
        default -> Integer.parseInt(record.get("qos").getAsString());
      };
    }
    if (qos < 0 || qos > 2) {
      throw new IllegalArgumentException("Invalid QoS");
    }
    boolean retained = present(record, "retained") && record.get("retained").getAsBoolean();
    return new Event(timestamp, record.get("topic").getAsString(), bytes, qos, retained);
  }

  private static boolean present(JsonObject record, String field) {
    return record.has(field) && !record.get(field).isJsonNull();
  }

  static final class EventReader implements AutoCloseable {
    private final List<Source> sources = new ArrayList<>();
    private final PriorityQueue<Source> queue = new PriorityQueue<>(
        Comparator.comparing((Source source) -> source.current.timestamp())
            .thenComparingInt(source -> source.index));

    EventReader(Path directory, int qos) throws IOException {
      try {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(directory)) {
          files = stream.filter(Files::isRegularFile)
              .filter(EventReader::supported)
              .sorted()
              .toList();
        }
        if (files.isEmpty()) {
          throw new IOException("No .ndjson or .ndjson.gz files in " + directory);
        }
        for (Path file : files) {
          Source source = new Source(file, sources.size(), qos);
          sources.add(source);
          if (source.advance()) {
            queue.add(source);
          }
        }
      } catch (IOException | RuntimeException exception) {
        try {
          close();
        } catch (IOException cleanup) {
          exception.addSuppressed(cleanup);
        }
        throw exception;
      }
    }

    private static boolean supported(Path path) {
      String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
      return filename.endsWith(".ndjson") || filename.endsWith(".ndjson.gz");
    }

    Event next() throws IOException {
      Source source = queue.poll();
      if (source == null) {
        return null;
      }
      Event event = source.current;
      if (source.advance()) {
        if (source.current.timestamp().isBefore(event.timestamp())) {
          throw new IOException("Input file is not timestamp ordered: " + source.path);
        }
        queue.add(source);
      }
      return event;
    }

    @Override
    public void close() throws IOException {
      IOException error = null;
      for (Source source : sources) {
        try {
          source.reader.close();
        } catch (IOException exception) {
          if (error == null) {
            error = exception;
          } else {
            error.addSuppressed(exception);
          }
        }
      }
      if (error != null) {
        throw error;
      }
    }

    private static final class Source {
      private final Path path;
      private final int index;
      private final int qos;
      private final BufferedReader reader;
      private long lineNumber;
      private Event current;

      Source(Path path, int index, int qos) throws IOException {
        this.path = path;
        this.index = index;
        this.qos = qos;
        this.reader = path.toString().toLowerCase(Locale.ROOT).endsWith(".gz")
            ? new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))
            : Files.newBufferedReader(path, StandardCharsets.UTF_8);
      }

      boolean advance() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
          lineNumber++;
          if (line.isBlank()) {
            continue;
          }
          try {
            current = decode(line, qos);
          } catch (RuntimeException exception) {
            throw new IOException("Invalid event " + path + ":" + lineNumber, exception);
          }
          return true;
        }
        current = null;
        return false;
      }
    }
  }

  static final class Publisher implements AutoCloseable {
    private final String broker;
    private final boolean sessionPerTopic;
    private final Map<String, MqttClient> clients = new HashMap<>();

    Publisher(String broker, boolean sessionPerTopic) {
      this.broker = broker;
      this.sessionPerTopic = sessionPerTopic;
    }

    void publish(Event event) throws Exception {
      String key = sessionPerTopic ? event.topic() : "";
      MqttClient client = clients.get(key);
      if (client == null) {
        client = new MqttClient(broker, "maps-mqtt-replay-" + UUID.randomUUID(), new MemoryPersistence());
        try {
          MqttConnectOptions options = new MqttConnectOptions();
          options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
          options.setCleanSession(true);
          options.setAutomaticReconnect(false);
          client.connect(options);
        } catch (Exception exception) {
          client.close();
          throw exception;
        }
        clients.put(key, client);
      }
      MqttMessage message = new MqttMessage(event.payload());
      message.setQos(event.qos());
      message.setRetained(event.retained());
      client.publish(event.topic(), message);
    }

    int sessionCount() {
      return clients.size();
    }

    @Override
    public void close() throws Exception {
      Exception error = null;
      for (MqttClient client : clients.values()) {
        try {
          if (client.isConnected()) {
            client.disconnect();
          }
          client.close();
        } catch (Exception exception) {
          if (error == null) {
            error = exception;
          } else {
            error.addSuppressed(exception);
          }
        }
      }
      clients.clear();
      if (error != null) {
        throw error;
      }
    }
  }
}
