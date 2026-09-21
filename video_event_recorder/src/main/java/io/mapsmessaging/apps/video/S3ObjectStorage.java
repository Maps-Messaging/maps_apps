package io.mapsmessaging.apps.video;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

final class S3ObjectStorage implements ObjectStorage {

  private static final DateTimeFormatter DATE_PATH =
      DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

  private final S3Client s3Client;
  private final S3Presigner presigner;
  private final String bucket;
  private final String prefix;
  private final Duration urlValidity;

  S3ObjectStorage(VideoRecorderConfig config) {
    Region region = Region.of(config.s3Region());
    this.s3Client =
        S3Client.builder()
            .region(region)
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    this.presigner = S3Presigner.builder().region(region).build();
    this.bucket = config.s3Bucket();
    this.prefix = config.s3Prefix();
    this.urlValidity = Duration.ofSeconds(config.urlValiditySeconds());
  }

  S3ObjectStorage(
      S3Client s3Client,
      S3Presigner presigner,
      String bucket,
      String prefix,
      Duration urlValidity) {
    this.s3Client = s3Client;
    this.presigner = presigner;
    this.bucket = bucket;
    this.prefix = prefix;
    this.urlValidity = urlValidity;
  }

  @Override
  public StoredObject upload(Path file, RecordingRequest request) {
    String key = objectKey(prefix, request);

    PutObjectRequest putRequest =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("video/mp4")
            .build();
    s3Client.putObject(putRequest, RequestBody.fromFile(file));

    GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(urlValidity)
            .getObjectRequest(getRequest)
            .build();
    PresignedGetObjectRequest presigned = presigner.presignGetObject(presignRequest);
    return new StoredObject(key, URI.create(presigned.url().toString()));
  }

  static String objectKey(String prefix, RecordingRequest request) {
    String date = DATE_PATH.format(request.eventTime());
    StringBuilder key = new StringBuilder();
    if (prefix != null && !prefix.isBlank()) {
      key.append(trimSlashes(prefix)).append('/');
    }
    key.append(date)
        .append('/')
        .append(sanitize(request.sourceId()))
        .append('/')
        .append(sanitize(request.eventId()))
        .append('/')
        .append(sanitize(request.stream()))
        .append('-')
        .append(sanitize(request.requestId()))
        .append(".mp4");
    return key.toString();
  }

  private static String sanitize(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static String trimSlashes(String value) {
    return value.replaceAll("^/+", "").replaceAll("/+$", "");
  }

  @Override
  public void close() {
    presigner.close();
    s3Client.close();
  }
}
