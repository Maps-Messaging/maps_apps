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

## JSON transformation

The logger needs messages as `Message-JSON` envelopes (timestamps, QoS, session ID and the
other metadata fields). It requests this itself by appending `?Transformation=Message-JSON`
to its MQTT client id, and MAPS applies the transformation to that connection only. No server
configuration is needed.

**Do not** add a host-wide rule such as `tcp://127.0.0.1/*/*` → `Message-JSON`. It converts
the traffic of every local MQTT client (for example web viewers behind a local reverse proxy)
into envelopes and breaks them. To enforce the transformation on the server side, scope the
rule to the logger's account; see `install/maps-config-snippet.yaml`.

---

## Security

- The service runs as the unprivileged `maps-logger` account (created by the installer) under
  systemd sandboxing.
- Log files carry full message payloads. The log directory is `0750` and files are `0640`,
  owned by `maps-logger`; add analysts to the `maps-logger` group to grant read access.
  Upgrading from a version that ran as root moves existing files to the service account and
  removes world access.
- Broker credentials and TLS material are only ever read from files, never from command-line
  arguments or the env file. Keep them in `/etc/maps-logger/credentials/` (directory
  `root:maps-logger 0750`, files `root:maps-logger 0640`). They are re-read on every reconnect.
- Each archive created by the compression timer gets a SHA-256 entry in
  `/var/log/maps-logger/SHA256SUMS`. Copy it off-host together with the archives and check
  them with `sha256sum -c SHA256SUMS`.
- When the broker runs on the same host, confine the logger to loopback with
  `systemctl edit maps-logger`:

  ```ini
  [Service]
  IPAddressDeny=any
  IPAddressAllow=localhost
  ```

### Authenticated and mutual-TLS connections

Create the broker account and, for TLS, a keystore and trust store, then:

```bash
# Prompts without echo, so the password stays out of the shell history.
read -rsp 'Broker password: ' pw && printf '%s' "$pw" | \
  sudo install -m 0640 -o root -g maps-logger /dev/stdin \
  /etc/maps-logger/credentials/mqtt-password; unset pw
```

```bash
# /etc/maps-logger/env
MAPS_LOGGER_URL=ssl://127.0.0.1:1895
MAPS_LOGGER_USERNAME=maps-logger
MAPS_LOGGER_PASSWORD_FILE=/etc/maps-logger/credentials/mqtt-password
MAPS_LOGGER_KEYSTORE=/etc/maps-logger/credentials/logger-client.p12
MAPS_LOGGER_KEYSTORE_PASSWORD_FILE=/etc/maps-logger/credentials/keystore-password
MAPS_LOGGER_TRUSTSTORE=/etc/maps-logger/credentials/broker-trust.p12
MAPS_LOGGER_TRUSTSTORE_PASSWORD_FILE=/etc/maps-logger/credentials/truststore-password
```

The broker certificate must contain the host name or IP used in the URL as a subject
alternative name (for example `IP:127.0.0.1`); host name verification is always on. A
dedicated MAPS listener for the logger whose trust store holds only the logger's client
certificate keeps it separate from every other client.

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
/etc/maps-logger/credentials/        ← root:maps-logger 0750
/var/log/maps-logger/                ← maps-logger:maps-logger 0750
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
correctly. The image runs as the unprivileged `maps-logger` user; mount credential files
read-only and reference them with the `*_FILE` variables.

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
| `MAPS_LOGGER_URL` | *(required)* | MQTT broker URL, e.g. `tcp://127.0.0.1:1883` or `ssl://127.0.0.1:1895` |
| `MAPS_LOGGER_TOPIC` | `#` | Topic filter(s); separate several with commas, e.g. `4817/#,/tak/cot/#` |
| `MAPS_LOGGER_QOS` | `1` | MQTT QoS level: 0, 1, or 2 |
| `MAPS_LOGGER_FORMAT` | `csv` | Output format: `csv` or `json` (json files use `.ndjson` extension) |
| `MAPS_LOGGER_OUTPUT_DIR` | `/var/log/maps-logger` | Directory for hourly output files |
| `MAPS_LOGGER_DISK_WARN_MB` | `500` | Free-space warning threshold in MB |
| `MAPS_LOGGER_USERNAME` | — | Broker user name |
| `MAPS_LOGGER_PASSWORD_FILE` | — | File containing the broker password (requires a user name) |
| `MAPS_LOGGER_KEYSTORE` | — | Client certificate keystore, PKCS12 or JKS (requires `ssl://`) |
| `MAPS_LOGGER_KEYSTORE_PASSWORD_FILE` | — | File containing the keystore password |
| `MAPS_LOGGER_TRUSTSTORE` | JVM default | Trust store for the broker certificate (requires `ssl://`) |
| `MAPS_LOGGER_TRUSTSTORE_PASSWORD_FILE` | — | File containing the trust store password |
| `MAPS_LOGGER_RETENTION_DAYS` | `0` | Delete compressed files older than this many days (0 = keep) |

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

Files are **never deleted** unless `MAPS_LOGGER_RETENTION_DAYS` is set. The compression timer
runs hourly, gzips files older than two hours and records each archive's SHA-256 in
`SHA256SUMS`. Disk space warnings appear in the system journal:

```
journalctl -u maps-logger | grep WARNING
```

---

## Troubleshooting

### No timestamps or missing metadata

The logger requests `Message-JSON` through its client id. If fields are still empty, check
that no server transformation rule overrides it for the logger's connection, or add the
account-scoped rule from `install/maps-config-snippet.yaml`.

### Connection refused, not authorised or TLS handshake errors

`journalctl -u maps-logger` shows the broker's reason. Check that the account exists on the
broker, that the credential files are readable by the `maps-logger` group, that the broker
trusts the logger's client certificate, and that the broker certificate's subject alternative
names contain the host or IP used in `MAPS_LOGGER_URL`.

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
