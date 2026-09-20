# MAPS UDP Helpers

The UDP Helpers capture packet-oriented UDP traffic and replay it later while preserving packet boundaries and timing. They are useful for MAVLink, NMEA 0183, AIS and similar UDP protocols.

## Installed commands

`maps-udp-capture` records UDP packets to the versioned MAPS capture format. `maps-udp-replay` replays those captures to a configured destination.

```bash
man maps-udp-capture
man maps-udp-replay
```

## Capture

```bash
maps-udp-capture --bind-address 0.0.0.0 --port 14550 --output flight.mudp
```

## Replay

```bash
maps-udp-replay --input flight.mudp --target-address 127.0.0.1 --target-port 14550
```

Replay can preserve original timing, change replay speed, or loop a capture. See the module README for the capture format and complete option set.
