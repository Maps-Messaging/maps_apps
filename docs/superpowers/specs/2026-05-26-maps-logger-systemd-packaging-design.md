# Design: MAPS Logger Systemd & Docker Packaging

**Date:** 2026-05-26  
**Module:** `mqtt_logger`

## Context

The MAPS Value Logger runs on a fleet of heterogeneous hosts:

- Raspberry Pi boxes (ARM Linux) — MAPS runs as `maps.service`
- Linux GCS laptop — MAPS runs as `maps.service`
- Central aggregator node — MAPS runs as a Docker image on Windows (and Linux) Docker Desktop

The logger must start automatically when MAPS is available, require no human intervention after initial install, and write hourly-rotating CSV (default) or NDJSON output to `/var/log/maps-logger/`. Disk space should be monitored with warnings written to the system log; no data is ever deleted automatically.

## MAPS Server Prerequisite

The following transformation block must be present in the MAPS server YAML config on every host. It ensures messages received on localhost TCP connections are delivered as JSON — the format the logger expects.

```yaml
transformations:
  data:
    - pattern: "*://*/*/*"
      transformation: ""

    - pattern: "tcp://127.0.0.1/*/*"
      transformation: "Message-JSON"
```

This snippet is shipped as `install/maps-config-snippet.yaml` for operators to paste into place.

---

## Section 1 — Java Changes (`mqtt_logger`)

### 1a. Environment Variable Fallback in `MapsValueLoggerArguments`

`parse()` gains a second resolution layer: if a CLI argument is absent, it checks the corresponding environment variable before failing. Priority order: **CLI arg > env var > built-in default**.

| Env var | CLI equivalent | Default |
|---|---|---|
| `MAPS_URL` | `--url` | *(required — no default)* |
| `MAPS_TOPIC` | `--topic` | `/#` |
| `MAPS_QOS` | `--qos` | `1` |
| `MAPS_FORMAT` | `--format` | `csv` |
| `MAPS_OUTPUT_DIR` | `--output-dir` *(new)* | `/var/log/maps-logger` |
| `MAPS_DISK_WARN_MB` | `--disk-warn-mb` | `500` |

`--output` (single file path) is retained for backward compatibility with manual use. If `--output-dir` / `MAPS_OUTPUT_DIR` is set it takes precedence and activates rolling mode.

### 1b. Hourly-Rolling Output

`LogWriterFactory` detects rolling mode (output dir configured) and creates a `RollingCsvLogWriter` or `RollingNdjsonLogWriter`. These wrap the existing writers. On every `write()` call the writer checks whether `Instant.now()` has moved into a new hour compared to the currently open file. If it has, the current file is closed and a new one opened.

Output filenames follow the pattern:

```
maps-YYYY-MM-DD-HH.csv
maps-YYYY-MM-DD-HH.ndjson
```

inside the configured output directory. The logger is responsible for "rotation" — logrotate is not involved in file creation or truncation.

### 1c. Disk Space Monitor

After each file roll (once per hour) the rolling writer calls `File.getFreeSpace()` on the output directory's filesystem. If free space is below `MAPS_DISK_WARN_MB` it writes a warning to `stderr`:

```
MAPS Logger WARNING: low disk space on /var/log/maps-logger — 234 MB free (threshold 500 MB)
```

`stderr` is captured by journald on native hosts (`journalctl -u maps-logger`) and by `docker logs` on the aggregator. **No data is ever deleted automatically at any threshold.**

On native hosts, a systemd timer compresses dated files older than 2 hours but never deletes them, keeping the directory tidy without data loss risk.

---

## Section 2 — Deployment Artifacts

### Repository layout

```
install/
  systemd/
    maps-logger.service           # systemd unit file
    maps-logger-compress.service  # one-shot compression unit
    maps-logger-compress.timer    # runs hourly, triggers compress service
    maps-logger.env               # env template → /etc/maps-logger/env
  docker/
    Dockerfile
    docker-compose.yml
  install.sh                      # idempotent install script (run as root)
  maps-config-snippet.yaml        # MAPS server YAML transformation block

scripts/
  build.sh                        # mvn clean package -pl mqtt_logger
  bundle.sh                       # build.sh + stage JAR + assemble install tarball
  deploy.sh                       # bundle.sh + scp + ssh install (one or more hosts)
```

### `maps-logger.service` (key directives)

```ini
[Unit]
After=network.target maps.service

[Service]
EnvironmentFile=/etc/maps-logger/env
ExecStart=java -jar /usr/lib/maps-logger/maps-value-logger.jar
Restart=on-failure
RestartSec=10s
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

All configuration is sourced from the environment file — no CLI args in the unit file.

### `maps-logger.env` template

```bash
MAPS_URL=tcp://127.0.0.1:1883
MAPS_TOPIC=/#
MAPS_QOS=1
MAPS_FORMAT=csv
MAPS_OUTPUT_DIR=/var/log/maps-logger
MAPS_DISK_WARN_MB=500
```

Operators edit this file once at install time. `systemctl edit maps-logger` can override individual values without touching the base file.

### `maps-logger-compress.service` / `maps-logger-compress.timer`

Since the logger writes dated files (never appending to a file from a previous hour), traditional logrotate rotation (rename + truncate) is unnecessary and incorrect. Instead, a systemd timer runs hourly and gzips files older than 2 hours:

```bash
# maps-logger-compress.service ExecStart
find /var/log/maps-logger -name "*.csv" -mmin +120 ! -name "*.gz" -exec gzip -f {} \;
find /var/log/maps-logger -name "*.ndjson" -mmin +120 ! -name "*.gz" -exec gzip -f {} \;
```

The timer fires hourly (`OnCalendar=hourly`). Files are never deleted — only compressed. `install.sh` enables the timer alongside the main service.

### `install.sh` behaviour (idempotent)

1. Checks Java 21+ is present; exits with a clear message if not
2. Creates `/usr/lib/maps-logger/`, `/etc/maps-logger/`, `/var/log/maps-logger/`
3. Copies the shaded JAR to `/usr/lib/maps-logger/`
4. Copies `maps-logger.service` to `/etc/systemd/system/`
5. Copies `maps-logger` logrotate config to `/etc/logrotate.d/`
6. Copies `maps-logger.env` to `/etc/maps-logger/env` **only if the file does not already exist** (preserves config on upgrades)
7. Runs `systemctl daemon-reload && systemctl enable maps-logger`
8. Prints a reminder: edit `/etc/maps-logger/env` then `systemctl start maps-logger`

### Scripts

**`scripts/build.sh`**
```bash
mvn clean package -pl mqtt_logger
```

**`scripts/bundle.sh`**  
Calls `build.sh`, copies the shaded JAR into `install/docker/` (so it is within the Docker build context), then assembles a tarball containing the JAR and the entire `install/` directory.

**`scripts/deploy.sh <user@host> [<user@host>...]`**  
Calls `bundle.sh` once, then for each host: `scp` the tarball and `ssh sudo ./install.sh`. Reports per-host success/failure; continues to remaining hosts on failure.

**Remote deployment example:**
```bash
./scripts/deploy.sh pi@192.168.1.10 pi@192.168.1.11 ubuntu@gcs-laptop
```

Compatible with Ansible's `script` module with no changes to `install.sh`.

---

## Section 3 — Docker Packaging

### `Dockerfile`

```dockerfile
FROM eclipse-temurin:21-jre
COPY maps-value-logger-*.jar /app/maps-value-logger.jar
RUN mkdir -p /var/log/maps-logger
ENTRYPOINT ["java", "-jar", "/app/maps-value-logger.jar"]
```

### `docker-compose.yml`

```yaml
services:
  maps:
    image: mapsmessaging/maps-server:latest
    volumes:
      - ./maps-config:/opt/maps/conf
      - logger-data:/var/log/maps-logger
    ports:
      - "1883:1883"

  maps-logger:
    build:
      context: .
    network_mode: "service:maps"
    environment:
      MAPS_URL: tcp://127.0.0.1:1883
      MAPS_TOPIC: /#
      MAPS_QOS: 1
      MAPS_FORMAT: csv
      MAPS_OUTPUT_DIR: /var/log/maps-logger
      MAPS_DISK_WARN_MB: 500
    volumes:
      - logger-data:/var/log/maps-logger
    depends_on:
      - maps
    restart: on-failure

volumes:
  logger-data:
```

### Key design decisions

**`network_mode: "service:maps"`** — the logger container shares the MAPS container's network namespace. `tcp://127.0.0.1:1883` in the logger resolves to the MAPS container's loopback, making the transformation pattern `tcp://127.0.0.1/*/*` match correctly. This works identically on Linux Docker and Windows Docker Desktop (unlike `network_mode: host` which is Linux-only).

**Shared volume `logger-data`** — both containers mount the same named Docker volume so log files written by the logger are accessible from the MAPS container if needed, and persist across `docker compose down`/`up` cycles.

**Log cleanup in Docker** — the logger writes dated files and monitors disk space. Automatic compression/cleanup is not configured for Docker; the operator manages the `logger-data` volume manually or via a scheduled `docker exec` task. Disk-space warnings appear in `docker logs maps-logger`.

**Same default URL everywhere** — `MAPS_URL=tcp://127.0.0.1:1883` is the default in both the systemd env template and the Docker Compose file. No per-environment URL differences to manage.
