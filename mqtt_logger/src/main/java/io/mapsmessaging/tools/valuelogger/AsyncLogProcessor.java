package io.mapsmessaging.tools.valuelogger;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.eclipse.paho.client.mqttv3.MqttMessage;

final class AsyncLogProcessor {

  static final int DEFAULT_QUEUE_CAPACITY = 2048;
  private static final long STOP_TIMEOUT_SECONDS = 5L;

  private final LogWriter logWriter;
  private final JsonLogRecordBuilder logRecordBuilder;
  private final Consumer<Throwable> failureHandler;
  private final BlockingQueue<QueuedMessage> queue;
  private final ExecutorService writerExecutor;
  private final AtomicBoolean running;
  private final AtomicBoolean failureSignalled;

  AsyncLogProcessor(
      LogWriter logWriter,
      JsonLogRecordBuilder logRecordBuilder,
      Consumer<Throwable> failureHandler) {
    this(
        logWriter,
        logRecordBuilder,
        failureHandler,
        DEFAULT_QUEUE_CAPACITY,
        Executors.newSingleThreadExecutor(namedThreadFactory("maps-logger-writer")));
  }

  AsyncLogProcessor(
      LogWriter logWriter,
      JsonLogRecordBuilder logRecordBuilder,
      Consumer<Throwable> failureHandler,
      int queueCapacity,
      ExecutorService writerExecutor) {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("queueCapacity must be positive");
    }

    this.logWriter = logWriter;
    this.logRecordBuilder = logRecordBuilder;
    this.failureHandler = failureHandler;
    this.queue = new ArrayBlockingQueue<>(queueCapacity);
    this.writerExecutor = writerExecutor;
    this.running = new AtomicBoolean(false);
    this.failureSignalled = new AtomicBoolean(false);
  }

  void start() {
    if (running.compareAndSet(false, true)) {
      writerExecutor.submit(this::runWriter);
    }
  }

  void onMessage(String topic, MqttMessage message, Runnable acknowledgement) {
    if (!running.get()) {
      return;
    }

    byte[] payload = Arrays.copyOf(message.getPayload(), message.getPayload().length);
    QueuedMessage queuedMessage = new QueuedMessage(topic, payload, acknowledgement);

    if (!queue.offer(queuedMessage)) {
      running.set(false);
      signalFailure(
          new IllegalStateException(
              "MAPS logger writer queue exhausted at " + queue.size() + " queued messages"));
    }
  }

  void stop() {
    running.set(false);
    writerExecutor.shutdown();

    try {
      if (!writerExecutor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        writerExecutor.shutdownNow();
      }
    } catch (InterruptedException exception) {
      writerExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  int queuedMessageCount() {
    return queue.size();
  }

  private void runWriter() {
    while (running.get() || !queue.isEmpty()) {
      try {
        QueuedMessage queuedMessage = queue.poll(250, TimeUnit.MILLISECONDS);
        if (queuedMessage == null) {
          continue;
        }

        String payload = new String(queuedMessage.payload(), StandardCharsets.UTF_8);
        logWriter.write(logRecordBuilder.build(queuedMessage.topic(), payload));
        queuedMessage.acknowledgement().run();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception exception) {
        running.set(false);
        signalFailure(new IllegalStateException("MAPS logger writer failed", exception));
        return;
      }
    }
  }

  private void signalFailure(Throwable failure) {
    if (failureSignalled.compareAndSet(false, true)) {
      System.err.println("MAPS Logger FATAL: " + failure.getMessage());
      failureHandler.accept(failure);
    }
  }

  private static ThreadFactory namedThreadFactory(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(false);
      return thread;
    };
  }

  private record QueuedMessage(
      String topic,
      byte[] payload,
      Runnable acknowledgement) {
  }
}
