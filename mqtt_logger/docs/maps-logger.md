# MAPS Value Logger

The MAPS Value Logger subscribes to MAPS MQTT v5 traffic and writes selected message-envelope fields to rotating CSV or NDJSON logs for later operational analysis.

## Installation

Install the `maps-apps` Debian package. The package installs and manages the logger as a systemd service and installs its manual pages.

Useful references:

```bash
man 8 maps-logger
man 5 maps-logger.env
```

Configuration is stored in `/etc/maps-logger/env`. Logs normally live under `/var/log/maps-logger`.

## Operation

```bash
systemctl status maps-logger
journalctl -u maps-logger
```

The logger supports MQTT URL, one or more topic filters, QoS, CSV/NDJSON format, output directory and disk-space warning configuration, plus broker authentication and mutual TLS with credentials read from files.

It runs as the unprivileged `maps-logger` account; output files are readable by the `maps-logger` group only, and compressed archives are listed with their SHA-256 in `/var/log/maps-logger/SHA256SUMS`. See the module README and INSTALL.md for the complete configuration, security and deployment procedure.
