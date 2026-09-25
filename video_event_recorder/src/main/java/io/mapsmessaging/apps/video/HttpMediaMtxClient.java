package io.mapsmessaging.apps.video;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

final class HttpMediaMtxClient implements MediaMtxClient {

  @FunctionalInterface
  interface Downloader {
    int download(URI uri, String authorization, Path target) throws Exception;
  }

  private final String playbackUrl;
  private final String authorization;
  private final Downloader downloader;

  HttpMediaMtxClient(VideoRecorderConfig config) {
    this(
        config.mediaMtxPlaybackUrl(),
        config.mediaMtxUsername(),
        config.mediaMtxPassword(),
        new JdkDownloader());
  }

  HttpMediaMtxClient(
      String playbackUrl, String username, String password, Downloader downloader) {
    this.playbackUrl = stripTrailingSlash(playbackUrl);
    this.authorization = basicAuthorization(username, password);
    this.downloader = downloader;
  }

  @Override
  public Path download(String stream, Instant start, int durationSeconds) throws Exception {
    Path target = Files.createTempFile("maps-video-event-", ".mp4");
    boolean success = false;
    try {
      int status = downloader.download(buildPlaybackUri(stream, start, durationSeconds), authorization, target);
      if (status < 200 || status >= 300) {
        throw new IOException("MediaMTX playback returned HTTP " + status);
      }
      success = true;
      return target;
    } finally {
      if (!success) {
        Files.deleteIfExists(target);
      }
    }
  }

  URI buildPlaybackUri(String stream, Instant start, int durationSeconds) {
    String query =
        "path="
            + encode(stream)
            + "&start="
            + encode(start.toString())
            + "&duration="
            + durationSeconds
            + "&format=mp4";
    return URI.create(playbackUrl + "/get?" + query);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String basicAuthorization(String username, String password) {
    if (username == null || username.isBlank()) {
      return null;
    }
    String credentials = username + ":" + (password == null ? "" : password);
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private static String stripTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static final class JdkDownloader implements Downloader {

    private final HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public int download(URI uri, String authorization, Path target) throws Exception {
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET();
      if (authorization != null) {
        requestBuilder.header("Authorization", authorization);
      }
      HttpResponse<Path> response =
          client.send(
              requestBuilder.build(),
              HttpResponse.BodyHandlers.ofFile(
                  target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
      return response.statusCode();
    }
  }
}
