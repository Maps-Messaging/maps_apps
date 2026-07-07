package io.mapsmessaging.canbus.app;

import io.mapsmessaging.canbus.device.SocketCanDevice;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CanbusNdjsonReplayApp {

  private CanbusNdjsonReplayApp() {
  }

  public static void main(String[] args) throws Exception {
    ReplayConfig config = ReplayConfig.parse(args);
    List<ReplayRecord> records = ReplayLoader.load(config.directory());

    if (records.isEmpty()) {
      System.err.println("No replayable CAN records found in " + config.directory());
      return;
    }

    AtomicBoolean running = new AtomicBoolean(true);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      running.set(false);
      System.out.println("Shutdown requested...");
    }, "shutdown-hook"));

    try (SocketCanDevice writer = new SocketCanDevice(config.interfaceName())) {
      System.out.println("Opened writer on " + config.interfaceName() + " capabilities=" + writer.getCanCapabilities());
      System.out.println("Loaded replay records: " + records.size());
      System.out.println("Replay speed: " + config.speed() + "x");
      System.out.println("Loop: " + config.loop());
      System.out.println("Press Ctrl+C to stop.");

      ReplayRunner runner = new ReplayRunner(records, writer, running, config.loop(), config.speed());
      runner.run();
    }
  }
}