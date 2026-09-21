package io.mapsmessaging.apps.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class VideoEventRecorder implements AutoCloseable {

  private static final int PUBLISH_ATTEMPTS = 3;

  private final VideoRecorderConfig config;
  private final RecordingTransport transport;
  private final MediaMtxClient mediaMtxClient;
  private final ObjectStorage objectStorage;
  private final DeduplicationRegistry deduplicationRegistry;
  private final ScheduledExecutorService scheduler;
  private final Clock clock;
  private final long publishRetryDelayMillis;

  VideoEventRecorder(
      VideoRecorderConfig config,
      RecordingTransport transport,
      MediaMtxClient mediaMtxClient,
      ObjectStorage objectStorage) {
    this(
        config,
        transport,
        mediaMtxClient,
        objectStorage,
        new DeduplicationRegistry(),
        Executors.newScheduledThreadPool(2),
        Clock.systemUTC(),
        1000L);
  }

  VideoEventRecorder(
      VideoRecorderConfig config,
      RecordingTransport transport,
      MediaMtxClient mediaMtxClient,
      ObjectStorage objectStorage,
      DeduplicationRegistry deduplicationRegistry,
      ScheduledExecutorService scheduler,
      Clock clock,
      long publishRetryDelayMillis) {
    this.config = config;
    this.transport = transport;
    this.mediaMtxClient = mediaMtxClient;
    this.objectStorage = objectStorage;
    this.deduplicationRegistry = deduplicationRegistry;
    this.scheduler = scheduler;
    this.clock = clock;
    this.publishRetryDelayMillis = publishRetryDelayMillis;
  }

  void start() throws Exception {
    transport.start(this::onRequest);
  }

  void onRequest(String payload) {
    RecordingRequest request;
    try {
      request = RecordingRequest.fromJson(payload, config);
    } catch (RuntimeException exception) {
      System.err.println("Ignoring invalid video recording request: " + exception.getMessage());
      return;
    }

    Instant now = clock.instant();
    boolean claimed =
        deduplicationRegistry.claim(
            request.deduplicationKey(),
            now,
            Duration.ofSeconds(config.dedupeRetentionSeconds()));
    if (!claimed) {
      return;
    }

    Instant readyAt =
        request.eventTime().plusSeconds(request.afterSeconds() + config.settleSeconds());
    long delayMillis = Math.max(0L, Duration.between(now, readyAt).toMillis());
    scheduler.schedule(() -> process(request), delayMillis, TimeUnit.MILLISECONDS);
  }

  void process(RecordingRequest request) {
    Path clip = null;
    RecordingResult result;
    try {
      clip =
          mediaMtxClient.download(
              request.stream(), request.startTime(), request.durationSeconds());
      ObjectStorage.StoredObject storedObject = objectStorage.upload(clip, request);
      result = RecordingResult.complete(request, storedObject);
    } catch (Exception exception) {
      result = RecordingResult.failed(request, exception);
    } finally {
      if (clip != null) {
        try {
          Files.deleteIfExists(clip);
        } catch (Exception exception) {
          System.err.println("Unable to remove temporary video clip: " + exception.getMessage());
        }
      }
    }

    publishResult(result);
  }

  private void publishResult(RecordingResult result) {
    Exception lastFailure = null;
    for (int attempt = 1; attempt <= PUBLISH_ATTEMPTS; attempt++) {
      try {
        transport.publish(result.toJson());
        return;
      } catch (Exception exception) {
        lastFailure = exception;
        if (attempt < PUBLISH_ATTEMPTS && publishRetryDelayMillis > 0) {
          try {
            Thread.sleep(publishRetryDelayMillis);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }

    String failureMessage = lastFailure == null ? "unknown error" : lastFailure.getMessage();
    System.err.println(
        "Unable to publish video recording result for "
            + result.requestId()
            + " after "
            + PUBLISH_ATTEMPTS
            + " attempts: "
            + failureMessage);
  }

  @Override
  public void close() throws Exception {
    scheduler.shutdownNow();
    Exception firstFailure = null;
    try {
      transport.close();
    } catch (Exception exception) {
      firstFailure = exception;
    }
    try {
      objectStorage.close();
    } catch (Exception exception) {
      if (firstFailure == null) {
        firstFailure = exception;
      } else {
        firstFailure.addSuppressed(exception);
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }
}
