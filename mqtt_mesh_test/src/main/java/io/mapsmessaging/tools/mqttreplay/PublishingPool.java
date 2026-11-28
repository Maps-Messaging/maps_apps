package io.mapsmessaging.tools.mqttreplay;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Single-reader, fixed-session publishing pool. Each worker owns its MQTT connection. */
final class PublishingPool implements AutoCloseable {
  interface Session extends AutoCloseable {
    void publish(ReplayEvent event) throws Exception;
    default String publicationDiagnostics() { return "unavailable"; }
    void close() throws Exception;
  }

  interface SessionFactory {
    Session open(int index) throws Exception;
  }

  private final String mqttVersion;
  private final List<Worker> workers = new ArrayList<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicLong completed = new AtomicLong();
  private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(task -> {
    Thread thread = new Thread(task, "mqtt-replay-pool-monitor");
    thread.setDaemon(true);
    return thread;
  });
  private volatile boolean draining;
  private long lastDiagnosticSample = System.nanoTime();

  PublishingPool(int sessions, int capacity, SessionFactory factory, Consumer<ReplayEvent> callback) {
    this(sessions, capacity, factory, callback, "3.1.1");
  }

  PublishingPool(int sessions, int capacity, SessionFactory factory, Consumer<ReplayEvent> callback, String mqttVersion) {
    this.mqttVersion = mqttVersion;
    if (sessions <= 0 || capacity <= 0) {
      throw new IllegalArgumentException("Positive sessions and capacity required");
    }
    for (int index = 0; index < sessions; index++) {
      workers.add(new Worker(index, capacity, factory, callback));
    }
    for (Worker worker : workers) {
      worker.thread.start();
    }
    monitor.scheduleAtFixedRate(this::sample, 1, 1, TimeUnit.SECONDS);
  }

  static int sessionIndex(String topic, int sessions) {
    return Math.floorMod(topic.hashCode(), sessions);
  }

  int sessionCount() {
    return workers.size();
  }

  long completedCount() {
    return completed.get();
  }

  synchronized void publish(ReplayEvent event) throws Exception {
    checkFailure();
    if (draining) {
      throw new IllegalStateException("Publishing pool is draining");
    }
    Worker worker = workers.get(sessionIndex(event.topic(), workers.size()));
    if (!worker.queue.offer(event)) {
      worker.blockedCount.incrementAndGet();
      long start = System.nanoTime();
      worker.blockedSince = start;
      try {
        while (!worker.queue.offer(event, 100, TimeUnit.MILLISECONDS)) {
          checkFailure();
        }
      } finally {
        worker.blockedNanos.addAndGet(System.nanoTime() - start);
        worker.blockedSince = 0;
      }
    }
    checkFailure();
  }

  private void checkFailure() throws Exception {
    Throwable error = failure.get();
    if (error != null) {
      throw new IllegalStateException("MQTT publishing failed after " + completed.get()
          + " successful client publications; replay incomplete", error);
    }
  }

  private synchronized void stopAccepting() {
    draining = true;
  }

  void drain() throws Exception {
    stopAccepting();
    for (Worker worker : workers) {
      worker.thread.join();
    }
    checkFailure();
  }

  @Override
  public void close() throws Exception {
    stopAccepting();
    boolean interrupted = false;
    try {
      for (Worker worker : workers) {
        while (worker.thread.isAlive()) {
          try {
            worker.thread.join();
          } catch (InterruptedException exception) {
            interrupted = true;
          }
        }
      }
      checkFailure();
      if (interrupted) {
        throw new InterruptedException("Interrupted while draining publishing pool");
      }
    } finally {
      monitor.shutdownNow();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static boolean degraded(double rate, double median, boolean busy, long durationNanos) {
    return busy && median > 0 && rate < median * 0.25 && durationNanos >= TimeUnit.SECONDS.toNanos(10);
  }

  private void sample() {
    long now = System.nanoTime();
    for (Worker worker : workers) {
      long count = worker.published.get();
      long interval = Math.max(1, now - worker.previousSample);
      long busyNanos = worker.busyNanos(now);
      worker.rate = (count - worker.previousCount) * 1e9 / interval;
      worker.utilisation = (double) (busyNanos - worker.previousBusyNanos) / interval;
      worker.previousBusyNanos = busyNanos;
      worker.previousCount = count;
      worker.previousSample = now;
      worker.busy = !worker.queue.isEmpty() || worker.blockedSince != 0;
    }
    if (now - lastDiagnosticSample >= TimeUnit.SECONDS.toNanos(10)) {
      lastDiagnosticSample = now;
      for (Worker worker : workers) {
        Session session = worker.activeSession;
        if (session != null) {
          long active = worker.activeSince;
          long ageMillis = active == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(Math.max(0L, now - active));
          System.err.printf("Publisher %d MQTT %s: published=%,d, queued=%d, outstanding=%d, activeAgeMs=%d, %s%n",
              worker.index, mqttVersion, worker.published.get(), worker.queue.size(), active == 0 ? 0 : 1, ageMillis,
              session.publicationDiagnostics());
        }
      }
    }
    for (Worker worker : workers) {
      double[] peers = workers.stream().filter(peer -> peer != worker && (peer.busy || peer.utilisation >= 0.5) && peer.rate > 0)
          .mapToDouble(peer -> peer.rate).sorted().toArray();
      double median = peers.length == 0 ? 0 : (peers[(peers.length - 1) / 2] + peers[peers.length / 2]) / 2;
      if (worker.degradation.update(worker.rate, median, worker.busy, now)) {
        long blocked = worker.blockedNanos.get();
        long since = worker.blockedSince;
        if (since != 0) {
          blocked += Math.max(0, now - since);
        }
        long last = worker.lastSuccess;
        String lastSuccess = last == 0 ? "never" : String.format("%.1f sec ago", (now - last) / 1e9);
        System.err.printf("Degraded MQTT %s publishing session %d: %.1f events/sec, peer median %.1f, queue %d, last success %s, backpressure %,d, blocked %.3f sec%n",
            mqttVersion, worker.index, worker.rate, median, worker.queue.size(), lastSuccess,
            worker.blockedCount.get(), blocked / 1e9);
      }
    }
  }

  static final class DegradationTracker {
    private Long slowSince;

    boolean update(double rate, double median, boolean busy, long now) {
      if (!busy || median <= 0 || rate >= median * 0.25) {
        slowSince = null;
        return false;
      }
      if (slowSince == null) {
        slowSince = now;
      }
      if (degraded(rate, median, busy, now - slowSince)) {
        slowSince = now;
        return true;
      }
      return false;
    }
  }

  private final class Worker implements Runnable {
    private final int index;
    private final ArrayBlockingQueue<ReplayEvent> queue;
    private final SessionFactory factory;
    private final Consumer<ReplayEvent> callback;
    private final Thread thread;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong blockedCount = new AtomicLong();
    private final AtomicLong blockedNanos = new AtomicLong();
    private volatile long blockedSince;
    private volatile long lastSuccess;
    private volatile long activeSince;
    private volatile Session activeSession;
    private long totalBusyNanos;
    private long previousBusyNanos;
    private double utilisation;
    private final DegradationTracker degradation = new DegradationTracker();
    private long previousSample = System.nanoTime();
    private long previousCount;
    private double rate;
    private boolean busy;

    Worker(int index, int capacity, SessionFactory factory, Consumer<ReplayEvent> callback) {
      this.index = index;
      this.queue = new ArrayBlockingQueue<>(capacity);
      this.factory = factory;
      this.callback = callback;
      this.thread = new Thread(this, "mqtt-replay-publisher-" + index);
    }

    private synchronized void startPublish() {
      activeSince = System.nanoTime();
    }

    private synchronized void endPublish() {
      totalBusyNanos += System.nanoTime() - activeSince;
      activeSince = 0;
    }

    private synchronized long busyNanos(long now) {
      return totalBusyNanos + (activeSince == 0 ? 0 : Math.max(0, now - activeSince));
    }

    @Override
    public void run() {
      try (Session session = factory.open(index)) {
        activeSession = session;
        try {
          while (failure.get() == null && (!draining || !queue.isEmpty())) {
            ReplayEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) {
              startPublish();
              try {
                session.publish(event);
              } finally {
                endPublish();
              }
              lastSuccess = System.nanoTime();
              published.incrementAndGet();
              completed.incrementAndGet();
              callback.accept(event);
            }
          }
        } catch (Throwable error) {
          recordFailure(error);
          throw error;
        } finally {
          activeSession = null;
        }
      } catch (Throwable error) {
        recordFailure(error);
      }
    }
    private void recordFailure(Throwable error) {
      failure.compareAndSet(null, new IllegalStateException("MQTT " + mqttVersion + " publishing session " + index + " failed", error));
    }
  }
}
