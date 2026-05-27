# Maps Logger Installation

This guide installs and configures the Maps Logger service.

The logger subscribes to the configured MAPS/MQTT topic and writes received records to hourly
rotating files under:

```text
/var/log/maps-logger/maps-YYYY-MM-DD-HH.csv
```

Files are never deleted. Files older than two hours are automatically gzip-compressed by a
systemd timer. Disk space is monitored and a warning is written to the system log when free
space drops below the configured threshold.

---

## Important: enable JSON transformation

For valid logging, the MAPS server must transform MQTT messages into JSON before the logger
receives them.

Add the following block to your MAPS server configuration YAML (a ready-to-paste copy is
provided in `install/maps-config-snippet.yaml`):

```yaml
transformations:
  data:
    - pattern: "*://*/*/*"
      transformation: ""

    - pattern: "tcp://127.0.0.1/*/*"
      transformation: "Message-JSON"
```

The second rule applies only to connections from localhost, ensuring the logger receives
fully structured JSON envelopes containing timestamps, QoS, session ID, and all other
metadata fields.

---

## Package contents

Build and assemble the install tarball:

```bash
./scripts/bundle.sh
```

This produces `maps-logger-install.tar.gz` containing:

```text
maps-logger-bundle/
  install.sh
  maps-config-snippet.yaml
  mqtt_logger-1.0.0-SNAPSHOT.jar
  systemd/
    maps-logger.service
    maps-logger-compress.service
    maps-logger-compress.timer
    maps-logger.env
  docker/
    Dockerfile
    docker-compose.yml
```

---

## Native installation (Raspberry Pi / Linux GCS laptop)

Extract the tarball and run the installer as root:

```bash
tar -xzf maps-logger-install.tar.gz
sudo ./maps-logger-bundle/install.sh
```

The installer will:

- verify Java 21 or later is installed
- create `/usr/lib/maps-logger/`, `/etc/maps-logger/`, `/var/log/maps-logger/`
- install the logger JAR
- install the systemd unit files
- install the environment file (only on first install — not overwritten on upgrade)
- enable `maps-logger.service` and `maps-logger-compress.timer`
- restart `maps-logger` if it is already running (upgrade path)

### Installed layout

```text
/usr/lib/maps-logger/maps-value-logger.jar
/etc/maps-logger/env
/var/log/maps-logger/
/etc/systemd/system/maps-logger.service
/etc/systemd/system/maps-logger-compress.service
/etc/systemd/system/maps-logger-compress.timer
```

### Deploy to multiple hosts

```bash
./scripts/deploy.sh pi@192.168.1.10 pi@192.168.1.11 ubuntu@gcs-laptop
```

---

## Docker installation (aggregator node)

The logger can run as a sidecar alongside the MAPS server container. The logger container
shares the MAPS container's network namespace so that `tcp://127.0.0.1:1883` resolves
correctly and the `tcp://127.0.0.1/*/*` transformation rule matches.

Stage the JAR and start:

```bash
./scripts/bundle.sh
cd install/docker
docker compose up -d
```

Log files are written to the `logger-data` Docker volume and persist across restarts.

---

## Configuration

The logger is configured entirely via environment variables. On native hosts these are set
in `/etc/maps-logger/env`. In Docker they are set in `docker-compose.yml`.

| Variable | Default | Description |
|---|---|---|
| `MAPS_LOGGER_URL` | *(required)* | MQTT broker URL — must be `tcp://127.0.0.1:1883` |
| `MAPS_LOGGER_TOPIC` | `/#` | Topic filter to subscribe to |
| `MAPS_LOGGER_QOS` | `1` | MQTT QoS level: 0, 1, or 2 |
| `MAPS_LOGGER_FORMAT` | `csv` | Output format: `csv` or `json` (json files use `.ndjson` extension) |
| `MAPS_LOGGER_OUTPUT_DIR` | `/var/log/maps-logger` | Directory for hourly output files |
| `MAPS_LOGGER_DISK_WARN_MB` | `500` | Free-space warning threshold in MB |

Edit `/etc/maps-logger/env` then restart:

```bash
sudo systemctl restart maps-logger
```

To override individual values without editing the base file:

```bash
sudo systemctl edit maps-logger
```

---

## Service management

```bash
# Check status
systemctl status maps-logger --no-pager

# Follow live logs
journalctl -u maps-logger -f

# View recent logs
journalctl -u maps-logger -n 100 --no-pager

# Restart
sudo systemctl restart maps-logger

# Stop
sudo systemctl stop maps-logger
```

---

## Output files

Hourly files are created automatically:

```text
/var/log/maps-logger/maps-2026-05-27-14.csv
/var/log/maps-logger/maps-2026-05-27-15.csv
/var/log/maps-logger/maps-2026-05-27-14.csv.gz   ← compressed after 2 hours
```

Files are **never deleted**. The compression timer runs hourly and gzips files older than
two hours. Disk space warnings appear in the system journal:

```
journalctl -u maps-logger | grep WARNING
```

---

## Troubleshooting

### No timestamps or missing metadata

Verify the MAPS server YAML contains the `Message-JSON` transformation for
`tcp://127.0.0.1/*/*`. Without it the logger receives raw payloads rather than structured
JSON, and most fields will be empty.

### Service does not start

```bash
systemctl status maps-logger --no-pager
journalctl -u maps-logger -n 50 --no-pager
```

### Java not found or wrong version

The installer requires Java 21 or later. Check the installed version:

```bash
java -version
```

The logger is launched with:

```bash
java -jar /usr/lib/maps-logger/maps-value-logger.jar
```
