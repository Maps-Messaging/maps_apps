package io.mapsmessaging.apps.video;

import java.net.URI;
import java.nio.file.Path;

interface ObjectStorage extends AutoCloseable {

  record StoredObject(String objectKey, URI url) {}

  StoredObject upload(Path file, RecordingRequest request) throws Exception;

  @Override
  default void close() throws Exception {}
}
