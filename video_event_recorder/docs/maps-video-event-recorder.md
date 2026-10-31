# maps-video-event-recorder

`maps-video-event-recorder` is a protocol-neutral worker for producing event-centred video clips.

## Responsibilities

The worker deliberately knows only about:

- a generic MQTT request and result contract;
- a MediaMTX playback endpoint;
- MP4 clips;
- private S3 object storage;
- temporary presigned HTTPS URLs.

It does not interpret any upstream event or tasking protocol.

## Timing

For an event at time `T`, the requested recording interval is:

```text
start    = T - beforeSeconds
duration = beforeSeconds + afterSeconds
```

The worker delays retrieval until:

```text
T + afterSeconds + settleSeconds
```

This allows MediaMTX to finish recording the post-event portion before the playback request is made.

## Correlation and duplicate suppression

`requestId` correlates each request with its result. `eventId` identifies the underlying operational event. Duplicate suppression uses:

```text
eventId + "|" + stream
```

The entry expires after `MAPS_VIDEO_DEDUPE_RETENTION_SECONDS`.

## Failure behaviour

MediaMTX or S3 failures produce a generic `FAILED` result. Result publication is retried three times. Temporary MP4 files are deleted after each job regardless of success or failure.

A successful result is not created until the S3 upload has completed and a presigned URL has been generated.

## Security

- S3 objects are private.
- AWS credentials use the AWS SDK default provider chain.
- MediaMTX playback can use HTTP Basic authentication.
- MQTT can use username/password authentication.
- Use TLS/VPN protection when credentials or presigned URLs traverse untrusted networks.
- Presigned URLs are credentials and should be treated accordingly.

## Operational dependency

MediaMTX is not embedded in the application. It must run separately with recording and playback enabled.

The application expects the standard playback `/get` API supporting `path`, `start`, `duration`, and `format=mp4`.
