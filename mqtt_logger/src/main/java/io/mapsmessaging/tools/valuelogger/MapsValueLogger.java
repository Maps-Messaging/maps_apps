package io.mapsmessaging.tools.valuelogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MapsValueLogger {

  private final MapsValueLoggerArguments arguments;
  private final LogWriter logWriter;
  private final AsyncLogProcessor logProcessor;
  private final MapsMqttClient mapsMqttClient;
  private final CountDownLatch completion;
  private final AtomicReference<Throwable> failure;
  private final AtomicBoolean stopped;

  public MapsValueLogger(MapsValueLoggerArguments arguments) {
    this.arguments = arguments;
    this.logWriter = LogWriterFactory.create(arguments);
    this.completion = new CountDownLatch(1);
    this.failure = new AtomicReference<>();
    this.stopped = new AtomicBoolean(false);
    this.logProcessor =
        new AsyncLogProcessor(logWriter, new JsonLogRecordBuilder(), this::fail);
    this.mapsMqttClient =
        new MapsMqttClient(arguments, logProcessor::onMessage);
  }

  public void start() throws Exception {
    logWriter.open();
    logProcessor.start();

    try {
      mapsMqttClient.start();
    } catch (Exception exception) {
      stop();
      throw exception;
    }

    System.err.println(
        "Value logger started, format="
            + arguments.getOutputFormat());
  }

  public void await() throws Exception {
    completion.await();

    Throwable failureCause = failure.get();
    if (failureCause != null) {
      throw new IllegalStateException("MAPS logger stopped after a fatal error", failureCause);
    }
  }

  public void stop() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }

    try {
      mapsMqttClient.stop();
    } catch (Exception ignored) {
    }

    try {
      logProcessor.stop();
    } catch (Exception ignored) {
    }

    try {
      logWriter.close();
    } catch (Exception ignored) {
    }

    completion.countDown();
  }

  private void fail(Throwable failureCause) {
    if (failure.compareAndSet(null, failureCause)) {
      completion.countDown();
    }
  }
}
