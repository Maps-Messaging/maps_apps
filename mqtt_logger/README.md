# MAPS Value Logger

The MAPS Value Logger is a small command-line tool that subscribes to a MAPS MQTT v5 topic and logs selected envelope values from each received JSON message.

It is intended for simple capture and later analysis, especially in tools such as Excel.

The tool does not run inside the MAPS server. It is a standalone subscriber/logger.

## What it logs

The logger records generic MAPS message information only.

Current fields:

```text
receivedTimestamp
serverTimestamp
serverTimeMs
latencyMs
topic
contentType
identifier
qos
protocol
sessionId
```

`receivedTimestamp` is the local time when the logger received the message.

`serverTimeMs` is read from:

```text
meta.time_ms
```

`serverTimestamp` is the ISO-8601 form of `serverTimeMs`.

`latencyMs` is calculated as:

```text
receivedTimestamp - serverTimeMs
```

This assumes the logger machine and MAPS server clocks are reasonably aligned.

## Requirements

- Java 21 or later
- Maven
- A running MAPS server exposing MQTT v5
- JSON payloads from MAPS

## Build

From the project directory:

```bash
mvn clean package
```

This should produce a runnable JAR under `target/`.

The exact JAR name depends on the Maven project version and packaging configuration. Common examples are:

```text
target/maps-value-logger-<version>.jar
target/maps-value-logger-<version>-jar-with-dependencies.jar
```

Use the executable/fat JAR if the build creates one.

## Run

Basic CSV output:

```bash
java -jar target/maps-value-logger-<version>-jar-with-dependencies.jar \
  --url tcp://localhost:1883 \
  --topic "/#" \
  --qos 1 \
  --output maps-values.csv
```

CSV is the default format when the output file ends in `.csv`.

You can also specify the format explicitly:

```bash
java -jar target/maps-value-logger-<version>-jar-with-dependencies.jar \
  --url tcp://localhost:1883 \
  --topic "/#" \
  --qos 1 \
  --format csv \
  --output maps-values.csv
```

JSON line output:

```bash
java -jar target/maps-value-logger-<version>-jar-with-dependencies.jar \
  --url tcp://localhost:1883 \
  --topic "/#" \
  --qos 1 \
  --format json \
  --output maps-values.ndjson
```

If no output file is supplied, output is written to stdout:

```bash
java -jar target/maps-value-logger-<version>-jar-with-dependencies.jar \
  --url tcp://localhost:1883 \
  --topic "/#" \
  --qos 1
```

## Command-line arguments

| Argument | Required | Description |
|---|---:|---|
| `--url` | Yes | MQTT v5 broker URL, for example `tcp://localhost:1883` |
| `--topic` | Yes | Topic filter to subscribe to, for example `/#` |
| `--qos` | Yes | MQTT QoS level: `0`, `1`, or `2` |
| `--format` | No | Output format: `csv` or `json` |
| `--output` | No | Output file name. If omitted, writes to stdout |

## Output format selection

If `--format` is not supplied, the format is inferred from the output file name.

| Output file | Format |
|---|---|
| `*.csv` | CSV |
| `*.json` | NDJSON |
| `*.ndjson` | NDJSON |
| no output file | CSV |

## CSV output

CSV output includes a header row.

Example:

```csv
receivedTimestamp,serverTimestamp,serverTimeMs,latencyMs,topic,contentType,identifier,qos,protocol,sessionId
2026-05-26T10:42:11.123Z,2026-05-26T10:42:10.973Z,1779757730973,150,/events/example,mavlink,52,AT_MOST_ONCE,MavLink,127.0.0.1_64988_255
```

If the output file already exists and is not empty, the logger appends rows and does not write another header.

## JSON output

JSON output is newline-delimited JSON.

Each message is written as a single JSON object on one line.

Example:

```json
{"receivedTimestamp":"2026-05-26T10:42:11.123Z","serverTimestamp":"2026-05-26T10:42:10.973Z","serverTimeMs":1779757730973,"latencyMs":150,"topic":"/events/example","contentType":"mavlink","identifier":52,"qos":"AT_MOST_ONCE","protocol":"MavLink","sessionId":"127.0.0.1_64988_255"}
```

## Notes

- The tool currently assumes the incoming payload is valid JSON.
- The tool ignores `opaqueData`.
- The logger is intentionally generic and does not log protocol-specific fields.
- CSV is usually the best choice when the result will be opened in Excel.
- JSON line output is usually better for command-line processing or later ingestion into another system.

## Stopping the logger

Press `Ctrl-C`.

The tool disconnects from MQTT and closes the output file during shutdown.
