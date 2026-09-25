package io.mapsmessaging.apps.video;

import java.util.function.Consumer;

interface RecordingTransport extends AutoCloseable {

  void start(Consumer<String> requestHandler) throws Exception;

  void publish(String payload) throws Exception;

  @Override
  void close() throws Exception;
}
