# maps_apps

Standalone operational and diagnostic applications for the [MapsMessaging](https://mapsmessaging.io) platform.

## Modules

| Module | Commands or services | Purpose |
|---|---|---|
| [`mqtt_logger`](mqtt_logger/README.md) | `maps-logger` | Subscribe to MAPS MQTT messages and write rotating CSV or NDJSON logs |
| [`audit_viewer`](audit_viewer/README.md) | `maps-audit-viewer` | Read and verify tamper-evident Maps audit journals |
| [`udp_helpers`](udp_helpers/README.md) | `maps-udp-capture`, `maps-udp-replay`, `maps-udp-stream-convert` | Capture, replay, and convert packet-oriented UDP streams |
| [`mavlink_tools`](mavlink_tools/README.md) | `maps-mavlink-replay`, `maps-mavlink-inspect`, `maps-mavlink-convert`, `maps-mavlink-tlog-tail`, `maps-mavlink-tlog-system-id` | Inspect, convert, extract, rewrite, and replay MAVLink captures |
| `canbus_replay` | `maps-canbus-replay` | Replay NDJSON CAN records to SocketCAN |
| `top` | `maps-top` | Display live Maps server statistics in a terminal |

## Build

```bash
mvn clean verify
```

Deployable JARs are written below each module's `target` directory.

## Debian package

```bash
./packaging/scripts/build-debian.sh
```

The package installs application JARs below `/opt/maps/apps`, commands below `/usr/bin`, and manual pages below `/usr/share/man`.
