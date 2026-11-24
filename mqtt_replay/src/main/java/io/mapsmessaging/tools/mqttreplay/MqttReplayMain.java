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
      System.exit(1);
    }
  }

  static void replay(ReplayOptions options) throws Exception {
    long count = 0;
    ReplayScheduler scheduler = new ReplayScheduler(options.speed());
    try (NdjsonReplayReader reader = new NdjsonReplayReader(options.directory(), options.qos());
         MqttReplayPublisher publisher = new MqttReplayPublisher(options.broker(), options.sessionPerTopic(), options.sessionPrefix())) {
      ReplayEvent event;
      while ((event = reader.next()) != null) {
        scheduler.await(event.timestamp());
        publisher.publish(event);
        count++;
        if (count % 10000 == 0) {
          System.out.printf("Published %,d events using %d sessions%n", count, publisher.sessionCount());
        }
      }
      System.out.printf("Completed: %,d events, %d sessions%n", count, publisher.sessionCount());
    }
  }
}
