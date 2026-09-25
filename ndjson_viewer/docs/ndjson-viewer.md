# MAPS NDJSON Viewer

The MAPS NDJSON Viewer loads MAPS message-log NDJSON/JSONL into embedded DuckDB, Base64-decodes `opaqueData`, and exposes decoded JSON payloads for SQL analysis.

## Installation

Install the `maps-apps` Debian package. It installs `maps-ndjson-viewer` and the corresponding manual page.

```bash
man maps-ndjson-viewer
```

## Load and inspect logs

```bash
maps-ndjson-viewer /var/log/maps-logger --topics
maps-ndjson-viewer /var/log/maps-logger --interactive
```

The principal relations are `raw_log`, `maps_log`, and `mavlink_log`. Invalid Base64 or non-JSON payloads do not abort loading.

## DuckDB UI

```bash
maps-ndjson-viewer /var/log/maps-logger -ui
maps-ndjson-viewer --database telemetry.duckdb -ui
```

The UI uses the same embedded DuckDB instance, so imported and persisted relations are immediately available.

## MCP access

Local stdio transport:

```bash
maps-ndjson-viewer --database telemetry.duckdb --mcp
```

Streamable HTTP transport:

```bash
maps-ndjson-viewer --database telemetry.duckdb --mcp-http
```

The default endpoint is `http://127.0.0.1:8090/mcp`. Remote access can be enabled explicitly:

```bash
maps-ndjson-viewer --database telemetry.duckdb --mcp-http --bind 0.0.0.0 --port 8090
```

HTTP mode has no application authentication or TLS, so non-loopback access should be protected by a firewall, VPN, SSH tunnel, or authenticated HTTPS reverse proxy.

Both transports expose `list_relations`, `describe_relation`, `list_topics`, and `query_sql`. MCP SQL is read-only, external DuckDB access is disabled, and query results are limited to 200 rows by default with a maximum of 5,000.

## Persistent analysis

```bash
maps-ndjson-viewer /var/log/maps-logger --database telemetry.duckdb --topics
maps-ndjson-viewer --database telemetry.duckdb --interactive
```

For large captures this avoids paying the import cost for every investigation.

## Export

```bash
maps-ndjson-viewer --database telemetry.duckdb \
  --sql "SELECT receivedTimestamp, topic, payload FROM maps_log ORDER BY receivedTimestamp" \
  --output telemetry.csv
```

Use `--raw` when a query selects exactly one column and the output should contain the selected values without an envelope. This is useful for replayable JSONL payload extraction.

See the module README and `maps-ndjson-viewer(1)` for all options and interactive commands.
