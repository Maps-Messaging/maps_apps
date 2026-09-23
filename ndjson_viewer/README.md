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

Open the DuckDB UI against the loaded log data:

```bash
maps-ndjson-viewer /var/log/maps-logger -ui
```

The UI is started from the same embedded DuckDB instance, so `raw_log`, `maps_log`, `mavlink_log` and any persisted relations are immediately available. The viewer stays running while the UI is open; stop it with Ctrl-C.

## MCP server

Expose an existing DuckDB database to an MCP client over standard input/output:

```bash
maps-ndjson-viewer --database telemetry.duckdb --mcp
```

Or import logs first and expose the resulting in-memory DuckDB instance:

```bash
maps-ndjson-viewer /var/log/maps-logger --mcp
```

MCP mode exposes four tools:

- `list_relations` lists DuckDB tables and views.
- `describe_relation` lists columns and DuckDB types.
- `list_topics` lists all topics, or MAVLink topics only.
- `query_sql` executes read-only DuckDB SQL.

`query_sql` defaults to 200 rows and accepts at most 5,000 rows per call. MCP mode disables DuckDB external access so MCP SQL cannot read arbitrary local files or network resources. MCP JSON-RPC uses stdout; diagnostics remain on stderr.

Run one query:

```bash
maps-ndjson-viewer telemetry.ndjson \
  --sql "SELECT receivedTimestamp, topic, payload FROM maps_log WHERE topic = '/service/status' ORDER BY receivedTimestamp"
```

Persist the imported records for use from DBeaver or later analysis:

```bash
maps-ndjson-viewer telemetry.ndjson --database telemetry.duckdb --topics
```

Open an existing DuckDB database without reloading the original log files by omitting the input path:

```bash
maps-ndjson-viewer \
  --database telemetry.duckdb \
  --sql "SELECT receivedTimestamp, topic, payload FROM maps_log ORDER BY receivedTimestamp"
```

Or open that existing database directly in the DuckDB UI:

```bash
maps-ndjson-viewer --database telemetry.duckdb -ui
```

When no input path is supplied, the database file must already exist. The viewer opens it directly and does not call the log import path, so existing `raw_log`, `maps_log`, and other stored tables are left untouched.

DuckDB serves the UI locally. By default the UI frontend is fetched from `https://ui.duckdb.org`; queries and loaded data remain local unless MotherDuck is explicitly enabled. See the DuckDB UI extension documentation for deployment and offline considerations.

Export selected fields:

```bash
maps-ndjson-viewer telemetry.ndjson \
  --sql "SELECT receivedTimestamp, topic, json_extract_string(payload, '$.body.state') AS state FROM maps_log WHERE topic LIKE '%status%' ORDER BY receivedTimestamp" \
  --output status.csv
```

Export a single selected column without a result envelope with `--raw` or `--format raw`. Raw mode requires exactly one selected column. JSON values are written as JSON rather than quoted strings.

For example, extract all recorded STANAG task requests as replayable JSONL payloads from an existing database:

```bash
maps-ndjson-viewer \
  --database /Volumes/workVault/sesimbra-2/maps-logger.duckdb \
  --sql "SELECT payload FROM maps_log WHERE topic LIKE '%/MessageTypeEnum_TASK_ADMIN' ORDER BY receivedTimestamp" \
  --raw \
  --output tasking.jsonl
```

An output filename ending in `.csv` selects CSV. Other output filenames default to NDJSON. The format can be selected explicitly with `--format table`, `--format ndjson`, `--format csv`, or `--format raw`.

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
