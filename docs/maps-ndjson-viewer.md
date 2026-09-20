# MAPS NDJSON Viewer User Guide

## Overview

`maps-ndjson-viewer` is the MAPS Messaging log-analysis utility for NDJSON/JSONL message logs. It imports MAPS log envelopes into an embedded DuckDB database, Base64-decodes `opaqueData`, and exposes decoded JSON payloads for SQL analysis.

DuckDB is embedded in the application. A separate DuckDB server or command-line installation is not required.

## Installation

The viewer is installed as part of the `maps-apps` Debian package.

After installation:

- command: `/usr/bin/maps-ndjson-viewer`
- application JAR: `/opt/maps/apps/ndjson_viewer.jar`
- manual page: `/usr/share/man/man1/maps-ndjson-viewer.1.gz`

Verify the installation with:

```bash
command -v maps-ndjson-viewer
man maps-ndjson-viewer
```

The Debian packaging process collects module manual pages from `<module>/install/man/man[1-9]`, compresses them, and installs them below `/usr/share/man`.

## Supported input

The input can be a single log file or a directory. Directories are scanned recursively.

Supported file types are:

- `*.ndjson`
- `*.jsonl`
- `*.json`
- gzip-compressed versions of the above

Malformed Base64 or payloads that are not JSON do not stop the import. Fields that cannot be decoded or parsed are exposed as `NULL`.

## Data model

The viewer creates these primary DuckDB relations:

| Relation | Description |
| --- | --- |
| `raw_log` | Original imported records |
| `maps_log` | MAPS envelope columns plus decoded payload fields |
| `mavlink_log` | Convenience relation containing MAVLink records |

Useful `maps_log` fields include `receivedTimestamp`, `topic`, `decoded_text`, and `payload`.

`payload` is parsed JSON when the decoded `opaqueData` contains valid JSON.

## Basic use

Load a log directory and enter the interactive SQL prompt:

```bash
maps-ndjson-viewer /var/log/maps-logger
```

When running from an IDE or an environment without a Java console:

```bash
maps-ndjson-viewer /var/log/maps-logger --interactive
```

List topics:

```bash
maps-ndjson-viewer /var/log/maps-logger --topics
```

Run a single query:

```bash
maps-ndjson-viewer /var/log/maps-logger \
  --sql "SELECT receivedTimestamp, topic, payload FROM maps_log ORDER BY receivedTimestamp LIMIT 100"
```

## Persistent databases

For repeated analysis, import the logs into a persistent DuckDB database:

```bash
maps-ndjson-viewer /var/log/maps-logger \
  --database telemetry.duckdb \
  --topics
```

The database can subsequently be opened without rescanning the source logs:

```bash
maps-ndjson-viewer \
  --database telemetry.duckdb \
  --interactive
```

This is useful for large field captures because the import cost is paid once. The database can also be opened by DuckDB-compatible tools such as DBeaver.

## Interactive commands

| Command | Description |
| --- | --- |
| `.topics` | List topics and record counts |
| `.mavlink-topics` | List MAVLink topics and record counts |
| `.schema` | Describe `maps_log` |
| `.quit` | Exit the viewer |

Any other line is executed as DuckDB SQL.

## Common queries

### Select a topic

```sql
SELECT receivedTimestamp, payload
FROM maps_log
WHERE topic = '/service/status'
ORDER BY receivedTimestamp;
```

### Select a time range

```sql
SELECT receivedTimestamp, topic, payload
FROM maps_log
WHERE CAST(receivedTimestamp AS TIMESTAMPTZ)
      BETWEEN TIMESTAMPTZ '2026-09-20 14:00:00+00'
          AND TIMESTAMPTZ '2026-09-20 15:00:00+00'
ORDER BY receivedTimestamp;
```

### Exclude MAVLink traffic

```sql
SELECT receivedTimestamp, topic, payload
FROM maps_log
WHERE topic NOT LIKE '/mavlink/%'
ORDER BY receivedTimestamp;
```

### Select STANAG 4817 traffic

```sql
SELECT receivedTimestamp, topic, payload
FROM maps_log
WHERE topic LIKE '4817%'
   OR topic LIKE '/4817%'
   OR topic LIKE '%/4817/%'
ORDER BY receivedTimestamp;
```

### Inspect MAVLink packet loss

```sql
SELECT
  receivedTimestamp,
  TRY_CAST(json_extract_string(payload, '$.previousSequenceNumber') AS INTEGER) AS previous_seq,
  TRY_CAST(json_extract_string(payload, '$.currentSequenceNumber') AS INTEGER) AS current_seq,
  TRY_CAST(json_extract_string(payload, '$.lostPackets') AS INTEGER) AS lost
FROM mavlink_log
WHERE topic = '/mavlink/1/status'
  AND TRY_CAST(json_extract_string(payload, '$.lostPackets') AS INTEGER) > 0
ORDER BY receivedTimestamp;
```

## Exporting data

Write query results to CSV by using a `.csv` output filename:

```bash
maps-ndjson-viewer \
  --database telemetry.duckdb \
  --sql "SELECT receivedTimestamp, topic, payload FROM maps_log ORDER BY receivedTimestamp" \
  --output telemetry.csv
```

Write NDJSON explicitly:

```bash
maps-ndjson-viewer \
  --database telemetry.duckdb \
  --sql "SELECT receivedTimestamp, topic, payload FROM maps_log ORDER BY receivedTimestamp" \
  --format ndjson \
  --output telemetry.jsonl
```

Available output formats are `table`, `ndjson`, `csv`, and `raw`.

### Raw payload export

Use `--raw` when the query selects exactly one column and the output must contain the selected values without a result wrapper.

For example, export STANAG task messages as replayable JSONL:

```bash
maps-ndjson-viewer \
  --database telemetry.duckdb \
  --sql "SELECT payload FROM maps_log WHERE topic LIKE '%/MessageTypeEnum_TASK_ADMIN' ORDER BY receivedTimestamp" \
  --raw \
  --output tasking.jsonl
```

Raw JSON values are emitted as JSON rather than quoted JSON strings.

## Working with decoded payloads

Before writing JSON-path expressions, inspect the actual decoded representation:

```sql
SELECT topic, decoded_text
FROM maps_log
WHERE decoded_text IS NOT NULL
LIMIT 20;
```

Extract JSON fields with DuckDB JSON functions:

```sql
SELECT
  receivedTimestamp,
  topic,
  json_extract_string(payload, '$.body.state') AS state
FROM maps_log
WHERE payload IS NOT NULL
ORDER BY receivedTimestamp;
```

The available JSON paths depend on the message schema and the decoded payload recorded in the log.

## Operational guidance

For small captures, querying the source files directly is convenient. For large captures or repeated investigations, create a persistent `.duckdb` database first and perform subsequent queries against that database.

Use `raw_log` when investigating import or decoding problems, `maps_log` for general MAPS traffic analysis, and `mavlink_log` for MAVLink-specific analysis.

## Manual page

The installed command has a section 1 manual page:

```bash
man 1 maps-ndjson-viewer
```

The source is maintained at:

```text
ndjson_viewer/install/man/man1/maps-ndjson-viewer.1
```

The Debian package builder installs module manual pages automatically under `/usr/share/man`. Any new command added to `maps_apps` should provide its corresponding manual page under the module's `install/man/man<section>/` directory.
