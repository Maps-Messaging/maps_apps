package io.mapsmessaging.canbus.app;

import java.nio.file.Files;
import java.nio.file.Path;

public record ReplayConfig(Path directory, String interfaceName, boolean loop, double speed, long maxWaitMillis) {

  public static ReplayConfig parse(String[] args) {
    Path directory = null;
    String interfaceName = null;
    boolean loop = false;
    double speed = 1.0;
    long maxWaitMillis = 60_000; // 1 minute on a canbus is forever

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];

      switch (arg) {
        case "--dir" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("--dir requires a path");
          }
          directory = Path.of(args[++i]);
        }
        case "--interface", "--iface" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException(arg + " requires an interface name");
          }
          interfaceName = args[++i];
        }
        case "--loop" -> loop = true;
        case "--speed" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("--speed requires a number");
          }
          speed = Double.parseDouble(args[++i]);
          if (speed <= 0.0) {
            throw new IllegalArgumentException("--speed must be greater than zero");
          }
        }
        case "--max-wait-ms" -> {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException("--max-wait-ms requires a number");
          }
          maxWaitMillis = Long.parseLong(args[++i]);
          if (maxWaitMillis < 0) {
            throw new IllegalArgumentException("--max-wait-ms must be zero or greater");
          }
        }
        case "--help", "-h" -> {
          printUsage();
          System.exit(0);
        }
        default -> throw new IllegalArgumentException("Unknown argument: " + arg);
      }
    }

    if (directory == null) {
      throw new IllegalArgumentException("--dir is required");
    }

    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("Directory does not exist: " + directory);
    }

    if (interfaceName == null || interfaceName.isBlank()) {
      throw new IllegalArgumentException("--interface is required");
    }

    return new ReplayConfig(directory, interfaceName, loop, speed, maxWaitMillis);
  }

  private static void printUsage() {
    System.out.println("""
        Usage:
          java -jar canbus-replay.jar --dir <directory> --interface <can-interface> [--loop] [--speed <factor>] [--max-wait-ms <millis>]

        Options:
          --dir          Directory containing .ndjson files
          --interface    SocketCAN interface, for example vcan0 or can0
          --loop         Restart replay from the beginning when complete
          --speed        Replay speed multiplier, for example 10 means 10x faster
          --max-wait-ms  Maximum delay between replay records. Use 0 to remove all waits.
        """);
  }
}