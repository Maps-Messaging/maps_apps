# MAPS Value Logger

The MAPS Value Logger subscribes to a MAPS MQTT v5 topic and logs selected envelope fields
from each received JSON message to hourly-rotating CSV or NDJSON files.

It is intended for simple capture and later analysis, especially in tools such as Excel.
The tool does not run inside the MAPS server — it is a standalone subscriber/logger.

For service installation on Raspberry Pi, Linux GCS laptops, or Docker, see
[INSTALL.md](INSTALL.md).

---

## What it logs

| Field | Description |
|---|---|
| `receivedTimestamp` | Local time when the logger received the message (ISO-8601) |
| `serverTimestamp` | ISO-8601 form of `serverTimeMs` |
| `serverTimeMs` | Read from `meta.time_ms` in the JSON payload |
| `latencyMs` | `receivedTimestamp − serverTimeMs` (requires aligned clocks) |
| `topic` | MQTT topic |
| `contentType` | Message content type |
| `identifier` | Message identifier |
| `qos` | QoS level |
| `protocol` | Protocol name |
| `sessionId` | Session identifier |
| `metaVersion` | Metadata schema version |

---

## Requirements

- Java 21 or later
- Maven (to build)
- A running MAPS server exposing MQTT v5 with the `Message-JSON` transformation enabled
  (see [INSTALL.md — enable JSON transformation](INSTALL.md#important-enable-json-transformation))

---

## Build

```bash
mvn clean package -pl mqtt_logger
```

The shaded JAR lands at:

```text
mqtt_logger/target/mqtt_logger-1.0.0-SNAPSHOT.jar
```

---

## Run

### Service mode (recommended)

In production, the logger runs as a systemd service or Docker sidecar, configured entirely
via environment variables. See [INSTALL.md](INSTALL.md).

### Manual / ad-hoc use

Rolling output to a directory (default behaviour):

```bash
java -jar mqtt_logger/target/mqtt_logger-1.0.0-SNAPSHOT.jar \
  --url tcp://127.0.0.1:1883
# writes maps-YYYY-MM-DD-HH.csv to /var/log/maps-logger/ by default
```

Custom output directory:

```bash
java -jar mqtt_logger/target/mqtt_logger-1.0.0-SNAPSHOT.jar \
  --url tcp://127.0.0.1:1883 \
  --output-dir /tmp/logs
```

Single fixed file (no rotation):

```bash
java -jar mqtt_logger/target/mqtt_logger-1.0.0-SNAPSHOT.jar \
  --url tcp://127.0.0.1:1883 \
  --topic "/#" \
  --output maps-values.csv
```

NDJSON output:

```bash
java -jar mqtt_logger/target/mqtt_logger-1.0.0-SNAPSHOT.jar \
  --url tcp://127.0.0.1:1883 \
  --format json \
  --output-dir /tmp/logs
```

Stdout (pipe to another tool):

```bash
java -jar mqtt_logger/target/mqtt_logger-1.0.0-SNAPSHOT.jar \
  --url tcp://127.0.0.1:1883 \
  --output -
```

---

## Command-line arguments

| Argument | Default | Description |
|---|---|---|
| `--url` | *required* | MQTT v5 broker URL, e.g. `tcp://127.0.0.1:1883` |
| `--topic` | `/#` | Topic filter |
| `--qos` | `1` | MQTT QoS level: `0`, `1`, or `2` |
| `--format` | inferred / `csv` | Output format: `csv` or `json` |
| `--output-dir` | `/var/log/maps-logger` | Directory for hourly rotating files (activates rolling mode) |
| `--output` | — | Single fixed output file path (mutually exclusive with `--output-dir`) |
| `--disk-warn-mb` | `500` | Warn to stderr when free space drops below this many MB |

`--output` and `--output-dir` are mutually exclusive.

### Environment variables

All arguments can be set via environment variables. CLI args take precedence.

| Variable | Equivalent CLI arg |
|---|---|
| `MAPS_LOGGER_URL` | `--url` |
| `MAPS_LOGGER_TOPIC` | `--topic` |
| `MAPS_LOGGER_QOS` | `--qos` |
| `MAPS_LOGGER_FORMAT` | `--format` |
| `MAPS_LOGGER_OUTPUT_DIR` | `--output-dir` |
| `MAPS_LOGGER_DISK_WARN_MB` | `--disk-warn-mb` |

---

## Output files

### Rolling mode (default)

Hourly dated files are created in the output directory:

```text
/var/log/maps-logger/maps-2026-05-27-14.csv
/var/log/maps-logger/maps-2026-05-27-15.csv
/var/log/maps-logger/maps-2026-05-27-14.csv.gz   ← compressed after 2 hours
```

Files are **never deleted**. When installed as a service, a systemd timer gzips files
older than two hours. Disk space warnings are written to stderr (visible via
`journalctl -u maps-logger`).

### CSV format

```csv
receivedTimestamp,serverTimestamp,serverTimeMs,latencyMs,topic,contentType,identifier,qos,protocol,sessionId,metaVersion
2026-05-27T14:42:11.123Z,2026-05-27T14:42:10.973Z,1748354530973,150,/events/sensor,mavlink,52,AT_MOST_ONCE,MavLink,127.0.0.1_64988_255,1
```

### NDJSON format

Each message is one JSON object per line:

```json
{"receivedTimestamp":"2026-05-27T14:42:11.123Z","serverTimestamp":"2026-05-27T14:42:10.973Z","serverTimeMs":1748354530973,"latencyMs":150,"topic":"/events/sensor","contentType":"mavlink","identifier":52,"qos":"AT_MOST_ONCE","protocol":"MavLink","sessionId":"127.0.0.1_64988_255","metaVersion":"1"}
```

---

## Stopping the logger

Press `Ctrl-C`. The tool disconnects from MQTT and closes the current output file cleanly.
