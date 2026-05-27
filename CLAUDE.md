# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Maven multi-module project (Java 21) containing standalone CLI tools for the MapsMessaging platform. Two modules:

- **`mqtt_logger`** — subscribes to a MAPS MQTT v5 broker and logs message envelope fields to hourly-rotating CSV or NDJSON files; deployable as a systemd service or Docker sidecar
- **`audit_viewer`** — reads and verifies tamper-evident JSONL audit journals produced by MAPS

Both modules build shaded (fat) JARs via `maven-shade-plugin` with `Main-Class` set so `java -jar` works.

## Build & Test

```bash
# Build all modules
mvn clean package

# Build a single module
mvn clean package -pl mqtt_logger
mvn clean package -pl audit_viewer

# Run tests
mvn test

# Run tests for a single module
mvn test -pl mqtt_logger
```

The shaded JARs land in `<module>/target/<artifact>-<version>.jar`.

### Maven Profiles

- **`snapshot`** — enables the `maps_snapshots` repository at `repository.mapsmessaging.io`; required when `audit_viewer` depends on `simple_logging` SNAPSHOT artifacts
- **`release`** — attaches sources/javadoc, signs with GPG, and publishes to Sonatype Central

```bash
mvn clean package -Psnapshot   # resolve SNAPSHOT deps
mvn clean package -Prelease    # full release build (requires GPG key)
```

The `NVD_API_KEY` environment variable is needed for OWASP dependency-check scans (`dependency-check:check`).

## Deployment (mqtt_logger)

### Build and bundle

```bash
./scripts/bundle.sh          # builds JAR, assembles maps-logger-install.tar.gz
./scripts/deploy.sh pi@host  # bundle + scp + ssh install (multiple hosts supported)
```

### Install layout (native hosts)

```
/usr/lib/maps-logger/maps-value-logger.jar
/etc/maps-logger/env          ← edit this to configure
/var/log/maps-logger/         ← hourly rotating output files
/etc/systemd/system/maps-logger.service
/etc/systemd/system/maps-logger-compress.{service,timer}
```

### Configuration — environment variables

All configuration is read from `/etc/maps-logger/env` (native) or `docker-compose.yml` environment section (Docker). CLI args override env vars; env vars override built-in defaults.

| Variable | Default | Description |
|---|---|---|
| `MAPS_LOGGER_URL` | *(required)* | MQTT broker URL — must be `tcp://127.0.0.1:1883` |
| `MAPS_LOGGER_TOPIC` | `/#` | Topic filter |
| `MAPS_LOGGER_QOS` | `1` | MQTT QoS: 0, 1, or 2 |
| `MAPS_LOGGER_FORMAT` | `csv` | `csv` or `json` (json → `.ndjson` files) |
| `MAPS_LOGGER_OUTPUT_DIR` | `/var/log/maps-logger` | Rolling output directory |
| `MAPS_LOGGER_DISK_WARN_MB` | `500` | Free-space warning threshold |

### MAPS server prerequisite

The MAPS server YAML must include (see `install/maps-config-snippet.yaml`):

```yaml
transformations:
  data:
    - pattern: "*://*/*/*"
      transformation: ""
    - pattern: "tcp://127.0.0.1/*/*"
      transformation: "Message-JSON"
```

## Architecture

### mqtt_logger

Entry point: `MapsValueLoggerMain` → `MapsValueLoggerArguments.parse()` → `MapsValueLogger`.

**Argument resolution** (priority order): CLI arg → `MAPS_LOGGER_*` env var → built-in default. `--output` and `--output-dir` are mutually exclusive.

**`MapsValueLogger`** connects via Eclipse Paho MQTT v5, subscribes to the configured topic, and for each message calls `JsonLogRecordBuilder.build()` to extract envelope fields from the JSON payload. The resulting `JsonObject` is passed to a `LogWriter` from `LogWriterFactory`.

**`LogWriterFactory`** routing:
- `outputDir != null` (default) → `RollingLogWriter` — wraps `CsvLogWriter` or `NdjsonLogWriter`, rolls to a new dated file (`maps-YYYY-MM-DD-HH.csv/ndjson`) on each hour boundary, checks disk space after each roll
- `outputDir == null`, `--output <file>` given → `CsvLogWriter` or `NdjsonLogWriter` directly (single-file mode for manual use)

**`RollingLogWriter`** uses an injectable `Clock` and `LongSupplier` for testability. Disk space warnings go to stderr (captured by journald / `docker logs`). Files are never deleted; a systemd timer gzips files older than two hours.

### audit_viewer

Entry point: `AuditJournalViewCommand` → optionally loads an Ed25519 public key via `AuditKeyUtils` → constructs `AuditJournalViewer`.

`AuditJournalViewer` reads the JSONL audit journal line by line and delegates chain verification to `AuditVerifier` (from the `io.mapsmessaging:simple_logging` library dependency). Each record is annotated with `AuditRecordVerificationStatus` (`VALID`, `INVALID`, or `NOT_VERIFIED`) and wrapped in an `AuditRecordView`. `AuditJournalConsolePrinter` renders the list as a fixed-width console table with a summary count at the end.

The audit chain is hash-linked: each record contains a `previousRecordHash` and `recordHash`. A broken chain invalidates all subsequent records. Signature verification requires the `--public-key` argument; without it, only hash-chain and sequence integrity are checked.
