package io.mapsmessaging.canbus.app;

import io.mapsmessaging.canbus.device.SocketCanDevice;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReplayRunner {

  private final List<ReplayRecord> records;
  private final SocketCanDevice writer;
  private final AtomicBoolean running;
  private final boolean loop;
  private final double speed;

  public ReplayRunner(List<ReplayRecord> records, SocketCanDevice writer, AtomicBoolean running, boolean loop, double speed) {
    this.records = List.copyOf(records);
    this.writer = Objects.requireNonNull(writer, "writer");
    this.running = Objects.requireNonNull(running, "running");
    this.loop = loop;
    this.speed = speed;
  }

  public void run() throws IOException, InterruptedException {
    long totalSent = 0;

    do {
      totalSent = replayOnce(totalSent);
    } while (running.get() && loop);
  }

  private long replayOnce(long totalSent) throws IOException, InterruptedException {
    ReplayRecord previous = null;

    for (ReplayRecord current : records) {
      if (!running.get()) {
        return totalSent;
      }

      if (previous != null) {
        sleepBetween(previous, current);
      }

      writer.writeFrame(current.toCanFrame());
      totalSent++;

      if ((totalSent % 100) == 0) {
        System.out.println("TX stats: sent=" + totalSent + " lastTimestamp=" + current.timestamp());
      }

      previous = current;
    }

    return totalSent;
  }

  private void sleepBetween(ReplayRecord previous, ReplayRecord current) throws InterruptedException {
    long delayMillis = Duration.between(previous.timestamp(), current.timestamp()).toMillis();

    if (delayMillis <= 0) {
      return;
    }

    long adjustedDelayMillis = Math.max(1L, Math.round(delayMillis / speed));
    Thread.sleep(adjustedDelayMillis);
  }
}