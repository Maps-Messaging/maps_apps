package io.mapsmessaging.tools.valuelogger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

class AsyncLogProcessorTest {

  @Test
  void callbackReturnsWhileWriterIsBlockedAndAcknowledgesAfterWrite() throws Exception {
    CountDownLatch writerEntered = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    CountDownLatch acknowledged = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    LogWriter writer = new BlockingWriter(writerEntered, releaseWriter);
    AsyncLogProcessor processor =
        new AsyncLogProcessor(
            writer,
            new JsonLogRecordBuilder(),
            failure::set,
            2,
            Executors.newSingleThreadExecutor());

    processor.start();

    MqttMessage message =
        new MqttMessage("{\"value\":1}".getBytes(StandardCharsets.UTF_8));

    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        Duration.ofMillis(250),
        () -> processor.onMessage("/test", message, acknowledged::countDown));

    assertTrue(writerEntered.await(1, TimeUnit.SECONDS));
    assertFalse(acknowledged.await(100, TimeUnit.MILLISECONDS));

    releaseWriter.countDown();

    assertTrue(acknowledged.await(1, TimeUnit.SECONDS));
    processor.stop();
    assertNull(failure.get());
  }

  @Test
  void queueOverflowSignalsFatalFailureInsteadOfBlockingCallback() throws Exception {
    CountDownLatch writerEntered = new CountDownLatch(1);
    CountDownLatch releaseWriter = new CountDownLatch(1);
    CountDownLatch failed = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    AsyncLogProcessor processor =
        new AsyncLogProcessor(
            new BlockingWriter(writerEntered, releaseWriter),
            new JsonLogRecordBuilder(),
            throwable -> {
              failure.set(throwable);
              failed.countDown();
            },
            1,
            Executors.newSingleThreadExecutor());

    processor.start();

    MqttMessage message =
        new MqttMessage("{\"value\":1}".getBytes(StandardCharsets.UTF_8));

    processor.onMessage("/one", message, () -> {
    });
    assertTrue(writerEntered.await(1, TimeUnit.SECONDS));

    processor.onMessage("/two", message, () -> {
    });
    processor.onMessage("/three", message, () -> {
    });

    assertTrue(failed.await(1, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, failure.get());

    releaseWriter.countDown();
    processor.stop();
  }

  @Test
  void writerFailureSignalsFatalFailure() throws Exception {
    CountDownLatch failed = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    LogWriter writer = new LogWriter() {
      @Override
      public void open() {
      }

      @Override
      public void write(JsonObject logRecord) {
        throw new IllegalStateException("disk failed");
      }

      @Override
      public void close() {
      }
    };

    AsyncLogProcessor processor =
        new AsyncLogProcessor(
            writer,
            new JsonLogRecordBuilder(),
            throwable -> {
              failure.set(throwable);
              failed.countDown();
            },
            2,
            Executors.newSingleThreadExecutor());

    processor.start();
    processor.onMessage(
        "/test",
        new MqttMessage("{\"value\":1}".getBytes(StandardCharsets.UTF_8)),
        () -> {
        });

    assertTrue(failed.await(1, TimeUnit.SECONDS));
    assertInstanceOf(IllegalStateException.class, failure.get());

    processor.stop();
  }

  private static final class BlockingWriter implements LogWriter {

    private final CountDownLatch entered;
    private final CountDownLatch release;

    private BlockingWriter(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public void open() {
    }

    @Override
    public void write(JsonObject logRecord) throws Exception {
      entered.countDown();
      release.await();
    }

    @Override
    public void close() {
    }
  }
}
