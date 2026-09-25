package io.mapsmessaging.apps.video;

import java.nio.file.Path;
import java.time.Instant;

interface MediaMtxClient {

  Path download(String stream, Instant start, int durationSeconds) throws Exception;
}
