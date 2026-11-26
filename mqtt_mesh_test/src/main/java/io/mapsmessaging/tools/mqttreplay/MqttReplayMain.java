package io.mapsmessaging.tools.mqttreplay;

public final class MqttReplayMain {
  private MqttReplayMain() {
  }

  public static void main(String[] args) {
    try {
      ReplayOptions options = ReplayOptions.parse(args);
      replay(options);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      System.err.println("Replay interrupted");
      System.exit(130);
    } catch (Exception exception) {
      System.err.println("Replay failed: " + exception.getMessage());
      Throwable cause = exception.getCause();
      while (cause != null) {
        System.err.println("Caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        cause = cause.getCause();
      }
      System.exit(1);
    }
  }

  static void replay(ReplayOptions options) throws Exception {
    long count = 0;
    long lastCompleted = 0;
    ReplayScheduler scheduler = new ReplayScheduler(options.speed(), options.noDelay());
    try (NdjsonReplayReader reader = new NdjsonReplayReader(options.directory(), options.qos());
         ValidationCoordinator validation = new ValidationCoordinator(options, reader.topicCounts());
         MqttReplayPublisher publisher = new MqttReplayPublisher(options.broker(), options.sessionPerTopic(), options.sessionPrefix(), options.sessionIdleMillis(), options.sessions(), options.queueDepth(),
             event -> {
               if (!options.observeUrls().isEmpty()) {
                 validation.published(event);
               }
             })) {
      validation.start();
      if (options.sessions() > 0) {
        System.out.println("Publishing with a fixed session pool; published counts indicate client publish completion, not broker ingestion at QoS 0");
      }
      long lastReportNanos = System.nanoTime();
      ReplayEvent event;
      while ((event = reader.next()) != null) {
        scheduler.await(event.timestamp());
        publisher.publish(event);
        if (options.sessions() == 0 && !options.observeUrls().isEmpty()) {
          validation.published(event);
        }
        count++;
        if (count % 10000 == 0) {
          long now = System.nanoTime();
          long completed = options.sessions() == 0 ? count : publisher.completedCount();
          double eventsPerSecond = (completed - lastCompleted) * 1_000_000_000.0 / Math.max(1L, now - lastReportNanos);
          long totalRecords = reader.totalRecords();
          long recordsRead = reader.recordsRead();
          double progress = totalRecords == 0 ? 100.0 : 100.0 * recordsRead / totalRecords;
          System.out.printf("Published %,d events using %d sessions (%,.0f events/sec) (%.1f%% read: %,d/%,d records)%s%n",
              completed, publisher.sessionCount(), eventsPerSecond, progress, recordsRead, totalRecords,
              options.observeUrls().isEmpty() ? "" : validation.progress());
          lastCompleted = completed;
          lastReportNanos = now;
        }
      }
      publisher.drain();
      if (options.sessions() > 0 && count != publisher.completedCount()) {
        throw new IllegalStateException("Publishing pool did not complete all accepted events");
      }
      System.out.printf("Completed: %,d events, %,d invalid records skipped, %d sessions%n", count, reader.skippedRecords(), publisher.sessionCount());
      if (!options.observeUrls().isEmpty() && !validation.finish().successful()) {
        throw new IllegalStateException("One or more MQTT observation points failed validation");
      }
    }
  }
}
