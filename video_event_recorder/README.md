# Video Event Recorder

Protocol-agnostic event-triggered video capture for MapsMessaging deployments.

The recorder consumes generic JSON recording requests over MQTT, retrieves an MP4 clip from the MediaMTX playback service, uploads the clip to Amazon S3, and publishes a generic JSON result. It has no dependency on protocol-specific tasking schemas.

## Flow

```text
generic MQTT request
        |
        v
video_event_recorder
        |
        +--> wait until eventTime + afterSeconds
        |
        +--> MediaMTX /get playback API
        |
        +--> temporary MP4
        |
        +--> private S3 object
        |
        +--> presigned HTTPS URL
        |
        v
generic MQTT result
```

## Build

```bash
mvn -pl video_event_recorder -am clean verify
```

The shaded executable JAR is written below `video_event_recorder/target`.

## MediaMTX requirements

MediaMTX runs separately. Recording and playback must be enabled. A minimal example is:

```yaml
pathDefaults:
  record: yes
  recordFormat: fmp4
  recordPartDuration: 1s
  recordSegmentDuration: 1m
  recordDeleteAfter: 1h

playback: yes
playbackAddress: :9996
```

The recorder uses the MediaMTX playback endpoint:

```text
/get?path=<stream>&start=<RFC3339>&duration=<seconds>&format=mp4
```

See https://mediamtx.org/docs/features/playback and https://mediamtx.org/docs/features/record.

## Request

Default topic:

```text
maps/video/record/request
```

Example:

```json
{
  "requestId": "7c58af68-3d20-47ef-a0f0-707af7053ca7",
  "eventId": "detect-123",
  "sourceId": "USV-002",
  "stream": "optical_view",
  "eventTime": "2026-09-21T10:00:00Z",
  "beforeSeconds": 30,
  "afterSeconds": 60
}
```

`eventId` is optional and defaults to `requestId`. `beforeSeconds` and `afterSeconds` are optional and use configured defaults.

Duplicate requests are suppressed by `eventId + stream`, allowing optical and thermal clips for the same event while suppressing repeated requests for the same stream.

## Result

Default topic:

```text
maps/video/record/result
```

Successful result:

```json
{
  "requestId": "7c58af68-3d20-47ef-a0f0-707af7053ca7",
  "eventId": "detect-123",
  "sourceId": "USV-002",
  "stream": "optical_view",
  "status": "COMPLETE",
  "contentType": "video/mp4",
  "url": "https://...",
  "objectKey": "detections/2026/09/21/USV-002/detect-123/optical_view-7c58af68-3d20-47ef-a0f0-707af7053ca7.mp4",
  "startTime": "2026-09-21T09:59:30Z",
  "durationSeconds": 90
}
```

Failures use `status: FAILED` and include a `message` instead of a URL.

## Configuration

Required environment variables:

```text
MAPS_VIDEO_MQTT_URL=tcp://127.0.0.1:1883
MAPS_VIDEO_MEDIAMTX_URL=http://127.0.0.1:9996
MAPS_VIDEO_S3_BUCKET=repmus-detection-video
MAPS_VIDEO_S3_REGION=eu-central-1
```

Optional:

```text
MAPS_VIDEO_MQTT_USERNAME=
MAPS_VIDEO_MQTT_PASSWORD=
MAPS_VIDEO_REQUEST_TOPIC=maps/video/record/request
MAPS_VIDEO_RESULT_TOPIC=maps/video/record/result
MAPS_VIDEO_QOS=1
MAPS_VIDEO_MEDIAMTX_USERNAME=
MAPS_VIDEO_MEDIAMTX_PASSWORD=
MAPS_VIDEO_S3_PREFIX=detections
MAPS_VIDEO_BEFORE_SECONDS=30
MAPS_VIDEO_AFTER_SECONDS=60
MAPS_VIDEO_DEDUPE_RETENTION_SECONDS=1800
MAPS_VIDEO_URL_VALIDITY_SECONDS=86400
MAPS_VIDEO_SETTLE_SECONDS=2
```

AWS credentials are obtained through the standard AWS SDK default credential provider chain. Do not put AWS access keys in recorder configuration.

All environment settings have equivalent command-line options. Run:

```bash
maps-video-event-recorder --help
```

## S3 keys

Objects are stored as:

```text
<prefix>/<yyyy>/<MM>/<dd>/<sourceId>/<eventId>/<stream>-<requestId>.mp4
```

The object remains private. The published URL is a presigned GET URL with a configurable lifetime, limited to seven days by S3 signing.

## Installation

The top-level `maps-apps` Debian package installs the shaded JAR under `/opt/maps/apps` and generates the `maps-video-event-recorder` launcher under `/usr/bin`.

See [docs/maps-video-event-recorder.md](docs/maps-video-event-recorder.md) and `man maps-video-event-recorder`.
