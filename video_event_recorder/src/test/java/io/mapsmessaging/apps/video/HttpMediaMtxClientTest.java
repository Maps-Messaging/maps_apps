package io.mapsmessaging.apps.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpMediaMtxClientTest {

  @Test
  void buildsEncodedPlaybackRequestAndDownloadsMp4() throws Exception {
    AtomicReference<URI> requestedUri = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();

    HttpMediaMtxClient client =
        new HttpMediaMtxClient(
            "http://127.0.0.1:9996/",
            "user",
            "pass",
            (uri, auth, target) -> {
              requestedUri.set(uri);
              authorization.set(auth);
              Files.write(target, new byte[] {1, 2, 3});
              return 200;
            });

    Path clip =
        client.download(
            "optical/view", Instant.parse("2026-09-21T09:59:30Z"), 90);
    try {
      String rawQuery = requestedUri.get().getRawQuery();
      assertTrue(rawQuery.contains("path=optical%2Fview"));
      assertTrue(rawQuery.contains("start=2026-09-21T09%3A59%3A30Z"));
      assertTrue(rawQuery.contains("duration=90"));
      assertTrue(rawQuery.contains("format=mp4"));
      assertEquals("Basic dXNlcjpwYXNz", authorization.get());
      assertEquals(3, Files.size(clip));
    } finally {
      Files.deleteIfExists(clip);
    }
  }

  @Test
  void rejectsNonSuccessResponse() {
    HttpMediaMtxClient client =
        new HttpMediaMtxClient(
            "http://127.0.0.1:9996",
            null,
            null,
            (uri, auth, target) -> 404);

    assertThrows(
        IOException.class,
        () ->
            client.download(
                "optical_view", Instant.parse("2026-09-21T09:59:30Z"), 90));
  }
}
