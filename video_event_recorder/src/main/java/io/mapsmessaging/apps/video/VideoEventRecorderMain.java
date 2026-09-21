package io.mapsmessaging.apps.video;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public final class VideoEventRecorderMain {

  private VideoEventRecorderMain() {}

  public static void main(String[] args) {
    if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
      VideoRecorderConfig.printUsage();
      return;
    }

    VideoEventRecorder recorder = null;
    try {
      VideoRecorderConfig config = VideoRecorderConfig.parse(args);
      MqttTransport transport = new MqttTransport(config);
      HttpMediaMtxClient mediaMtxClient = new HttpMediaMtxClient(config);
      S3ObjectStorage objectStorage = new S3ObjectStorage(config);
      recorder = new VideoEventRecorder(config, transport, mediaMtxClient, objectStorage);

      VideoEventRecorder finalRecorder = recorder;
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      finalRecorder.close();
                    } catch (Exception exception) {
                      System.err.println(
                          "Video event recorder shutdown failed: " + exception.getMessage());
                    }
                  },
                  "maps-video-event-recorder-shutdown"));

      recorder.start();
      System.err.println(
          "Video event recorder started: requestTopic="
              + config.requestTopic()
              + ", resultTopic="
              + config.resultTopic());

      new CountDownLatch(1).await();
    } catch (IllegalArgumentException exception) {
      System.err.println(exception.getMessage());
      VideoRecorderConfig.printUsage();
      System.exit(2);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (Exception exception) {
      System.err.println("Video event recorder failed: " + exception.getMessage());
      if (recorder != null) {
        try {
          recorder.close();
        } catch (Exception ignored) {
        }
      }
      System.exit(1);
    }
  }
}
