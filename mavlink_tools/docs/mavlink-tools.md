# MAPS MAVLink Tools

The MAVLink Tools inspect, convert, extract, rewrite and replay MAVLink captures. Supported inputs include QGroundControl TLOG, MAPS MUDP captures, legacy demo captures and raw MAVLink streams.

## Installed commands

- `maps-mavlink-replay`
- `maps-mavlink-inspect`
- `maps-mavlink-convert`
- `maps-mavlink-tlog-tail`
- `maps-mavlink-tlog-system-id`

Each command has an installed section 1 manual page, for example:

```bash
man maps-mavlink-replay
man maps-mavlink-inspect
```

## Common operations

```bash
maps-mavlink-inspect --input flight.tlog --limit 20
maps-mavlink-convert --input flight.tlog --output flight.mudp
maps-mavlink-replay --input flight.tlog --target-address 127.0.0.1 --target-port 14550
```

Use `maps-udp-capture` from the UDP Helpers module for live UDP recording. See the module README for filtering, time windows, system-ID rewriting, dialect handling and signed-frame limitations.
