# MAVLink Tools

`mavlink_tools` provides supported command-line applications for inspecting, converting, and replaying MAVLink captures. The tools are installed by the `maps-apps` Debian package.

## Commands

| Command | Purpose |
|---|---|
| `maps-mavlink-replay` | Replay one or more MAVLink captures to a UDP destination |
| `maps-mavlink-inspect` | Display MAVLink frame header information and apply frame filters |
| `maps-mavlink-convert` | Convert captures into the versioned Maps UDP capture format (`MUDP`) |

Live UDP recording remains the responsibility of `maps-udp-capture`. This module adds MAVLink-aware file handling rather than introducing a second UDP recorder.

## Supported input formats

| Format | Typical extension | Notes |
|---|---|---|
| QGroundControl TLOG | `.tlog` | Eight-byte big-endian microsecond timestamp before each MAVLink frame |
| Maps UDP capture | `.mudp`, `.udp` | Versioned binary format produced by `maps-udp-capture` or `maps-mavlink-convert` |
| Legacy demo capture | `.dat`, `.csv` | Text records using `<delay_ms>,<base64_payload>` |
| Raw MAVLink stream | `.raw`, `.bin` | MAVLink frames scanned from a byte stream; use `--raw-delay` when timing is required |

`--format auto` detects MUDP files by their header and otherwise uses the filename extension. Explicit values are `auto`, `tlog`, `mudp`, `legacy-dat`, and `raw`.

Mission Planner `.rlog` files are not treated as a supported replay format because they can contain debug output mixed with MAVLink data. They may be examined explicitly with `--format raw`, but reliable replay should use the corresponding `.tlog` file.

## Capture live MAVLink UDP traffic

```bash
maps-udp-capture \
  --bind-address 0.0.0.0 \
  --port 14550 \
  --output flight.mudp
```

## Replay a capture

```bash
maps-mavlink-replay \
  --input flight.tlog \
  --target-address 127.0.0.1 \
  --target-port 14550
```

Replay several vehicle logs on one synchronised timeline:

```bash
maps-mavlink-replay \
  --input vehicle-1.tlog vehicle-2.tlog vehicle-3.tlog \
  --target-address 127.0.0.1 \
  --target-port 14450 \
  --speed 2 \
  --loop
```

Trim replay to a time window and select one vehicle:

```bash
maps-mavlink-replay \
  --input flight.tlog \
  --target-address 127.0.0.1 \
  --target-port 14550 \
  --start-offset 01:15 \
  --end-offset 05:00 \
  --system-id 10
```

Offsets accept seconds, `MM:SS`, `HH:MM:SS`, or values ending in `ms`, `s`, `m`, or `h`.

## Inspect captures

```bash
maps-mavlink-inspect --input flight.tlog --limit 20
```

Filter by system, component, or message identifier:

```bash
maps-mavlink-inspect \
  --input flight.tlog \
  --system-id 10 \
  --message-id 0,24,33,74
```

Inspection reports MAVLink version, system ID, component ID, message ID, sequence, payload length, signing flag, packet length, and the CRC field contained in the frame. It does not validate the message-specific CRC extra value.

## Convert to MUDP

```bash
maps-mavlink-convert \
  --input flight.tlog \
  --output flight.mudp
```

Merge and trim several timestamped logs:

```bash
maps-mavlink-convert \
  --input vehicle-1.tlog vehicle-2.tlog \
  --output mission.mudp \
  --start-offset 03:35 \
  --end-offset 05:00
```

The first output frame is timestamped at zero. Later records retain their relative spacing. The output can be replayed by either `maps-mavlink-replay` or `maps-udp-replay`.

## Installation

```bash
sudo apt install maps-apps
```

Manual pages:

```bash
man maps-mavlink-replay
man maps-mavlink-inspect
man maps-mavlink-convert
```

## Build

From the `maps_apps` repository root:

```bash
mvn clean verify
./packaging/scripts/build-debian.sh
```
