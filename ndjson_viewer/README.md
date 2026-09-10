# MAPS NDJSON Viewer

`maps-ndjson-viewer` loads MAPS message-log NDJSON into an embedded DuckDB database. It decodes the Base64 `opaqueData` column and exposes the decoded value as JSON for SQL filtering and field selection.

No DuckDB server or separate DuckDB installation is required. The JDBC driver runs inside the Java process.

## Input

The input can be one NDJSON file or a directory. Directories are searched recursively for:

- `*.ndjson`
- `*.jsonl`
- `*.json`
- gzip-compressed versions of those files

Each input record remains available in `raw_log`. Two relations are created for analysis:

| Relation | Purpose |
|---|---|
| `maps_log` | All outer envelope columns plus `decoded_text` and parsed JSON `payload` |
| `mavlink_log` | Records from `maps_log` whose topic contains `mavlink` |

Invalid Base64 or non-JSON payloads do not abort loading. `decoded_text` or `payload` is `NULL` when the corresponding conversion cannot be performed.

## Usage

List all topics:

```bash
maps-ndjson-viewer telemetry.ndjson --topics
```

Load a directory and open the interactive SQL prompt:

```bash
maps-ndjson-viewer /var/log/maps-logger
```

When launching from an IDE or another environment without a Java console, request the prompt explicitly:

```bash
maps-ndjson-viewer /var/log/maps-logger --interactive
```

Run one query:

```bash
maps-ndjson-viewer telemetry.ndjson \
  --sql "SELECT receivedTimestamp, topic, payload FROM maps_log WHERE topic = '/service/status' ORDER BY receivedTimestamp"
```

Persist the imported records for use from DBeaver or later analysis:

```bash
maps-ndjson-viewer telemetry.ndjson --database telemetry.duckdb --topics
```

Export selected fields:

```bash
maps-ndjson-viewer telemetry.ndjson \
  --sql "SELECT receivedTimestamp, topic, json_extract_string(payload, '$.body.state') AS state FROM maps_log WHERE topic LIKE '%status%' ORDER BY receivedTimestamp" \
  --output status.csv
```

An output filename ending in `.csv` selects CSV. Other output filenames default to NDJSON. The format can be selected explicitly with `--format table`, `--format ndjson`, or `--format csv`.

## Interactive commands

| Command | Action |
|---|---|
| `.topics` | List all topics and record counts |
| `.mavlink-topics` | List MAVLink topics and record counts |
| `.schema` | Describe `maps_log` |
| `.quit` | Exit |

Any other line is executed as DuckDB SQL.

## Useful queries

Filter a topic over a time interval:

```sql
SELECT receivedTimestamp, topic, payload
FROM maps_log
WHERE topic = '/service/status'
  AND CAST(receivedTimestamp AS TIMESTAMPTZ)
      BETWEEN TIMESTAMPTZ '2026-01-01 09:00:00+00'
          AND TIMESTAMPTZ '2026-01-01 10:00:00+00'
ORDER BY receivedTimestamp;
```

Inspect the decoded payload structure before selecting fields:

```sql
SELECT topic, decoded_text
FROM maps_log
LIMIT 20;
```

The precise JSON paths depend on the decoded representation present in the log.
