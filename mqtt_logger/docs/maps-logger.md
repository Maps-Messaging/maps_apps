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

The logger supports MQTT URL, topic, QoS, CSV/NDJSON format, output directory and disk-space warning configuration. See the module README and INSTALL.md for the complete configuration and deployment procedure.
