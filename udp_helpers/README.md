# UDP Helpers

`udp_helpers` provides small command line tools for capturing UDP packets to a replayable file, replaying that file back to a UDP target, and converting raw stream captures into the same replayable format.

The capture format preserves UDP packet boundaries and packet timing, which makes it useful for testing protocols such as MAVLink, NMEA 0183, AIS, or any other packet-oriented UDP stream.

## Tools

| Tool | Purpose |
|---|---|
| `udp-capture` | Listen on a UDP port and write received packets to a capture file |
| `udp-replay` | Read a capture file and send the packets to a UDP target |
| `udp-stream-convert` | Convert a raw stream file into a replayable UDP helper capture file |

## Package Layout

| Package | Contents |
|---|---|
| `io.mapsmessaging.tools.udphelpers` | Main classes |
| `io.mapsmessaging.tools.udphelpers.capture` | UDP capture arguments, capture logic, and capture writer |
| `io.mapsmessaging.tools.udphelpers.common` | Shared packet record model |
| `io.mapsmessaging.tools.udphelpers.replay` | UDP replay arguments, reader, and replay logic |
| `io.mapsmessaging.tools.udphelpers.convert` | Stream conversion arguments and converters |
| `io.mapsmessaging.tools.udphelpers.utils` | Small utility helpers |

## Capture Format

The file format is binary and packet-oriented.

Each file starts with:

| Field | Type |
|---|---|
| Magic | `MUDP` |
| Version | `int` |

Each packet record contains:

| Field | Type |
|---|---|
| Timestamp | `long`, captured using `System.nanoTime()` |
| Source address length | `int` |
| Source address bytes | `byte[]` |
| Source port | `int` |
| Payload length | `int` |
| Payload bytes | `byte[]` |

The timestamp is used to preserve packet spacing during replay.

## Capture UDP Packets

Example:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpCaptureMain \
  --bind-address 0.0.0.0 \
  --port 14550 \
  --output mavlink.udp
```

This listens on UDP port `14550` and writes received packets to `mavlink.udp`.

### Capture Options

| Option | Required | Default | Description |
|---|---:|---:|---|
| `--bind-address` | No | `0.0.0.0` | Address to bind to |
| `--port` | Yes | | UDP port to listen on |
| `--output` | Yes | | Output capture file |
| `--buffer-size` | No | `65535` | Maximum UDP packet buffer size |
| `--flush-each-packet` | No | `true` | Flush the file after each packet |
| `--hex-dump` | No | `false` | Print captured packet payloads as hex |
| `--quiet` | No | `false` | Disable packet logging |

## Replay UDP Packets

Example:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpReplayMain \
  --input mavlink.udp \
  --target-address 127.0.0.1 \
  --target-port 14550
```

This reads packets from `mavlink.udp` and sends them to `127.0.0.1:14550`.

### Replay Options

| Option | Required | Default | Description |
|---|---:|---:|---|
| `--input` | Yes | | Input capture file |
| `--target-address` | Yes | | Target UDP address |
| `--target-port` | Yes | | Target UDP port |
| `--preserve-timing` | No | `true` | Preserve captured packet spacing |
| `--speed` | No | `1.0` | Replay speed multiplier |
| `--loop` | No | `false` | Replay the file repeatedly |
| `--hex-dump` | No | `false` | Print replayed packet payloads as hex |
| `--quiet` | No | `false` | Disable packet logging |

## Convert Raw Streams

`udp-stream-convert` converts a raw stream file into the same binary capture format used by `udp-capture`.

The first converter is intended for NMEA 0183 style streams. It scans the input for `$` and treats each `$...` section as a packet payload. The generated records can then be replayed with `udp-replay`.

Example:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpStreamConvertMain \
  --input raw-nmea-stream.txt \
  --output nmea-capture.udp \
  --source-address 127.0.0.1 \
  --source-port 14550
```

Then replay the converted capture:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpReplayMain \
  --input nmea-capture.udp \
  --target-address 127.0.0.1 \
  --target-port 14550 \
  --preserve-timing false
```

### Stream Convert Options

| Option | Required | Default | Description |
|---|---:|---:|---|
| `--input` | Yes | | Input raw stream file |
| `--output` | Yes | | Output UDP helper capture file |
| `--source-address` | No | `127.0.0.1` | Source address stored in generated records |
| `--source-port` | No | `0` | Source port stored in generated records |
| `--flush-each-packet` | No | `true` | Flush the output file after each generated packet |
| `--quiet` | No | `false` | Disable packet logging |

## Replay Timing

By default, replay preserves the delay between captured packets.

For faster replay:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpReplayMain \
  --input mavlink.udp \
  --target-address 127.0.0.1 \
  --target-port 14550 \
  --speed 2.0
```

This replays packets at twice the original speed.

To replay without timing delays:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpReplayMain \
  --input mavlink.udp \
  --target-address 127.0.0.1 \
  --target-port 14550 \
  --preserve-timing false
```

Converted stream captures usually use generated timestamps, so `--preserve-timing false` is normally the correct replay mode for those files.

## Looping Replay

To replay the same capture repeatedly:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpReplayMain \
  --input mavlink.udp \
  --target-address 127.0.0.1 \
  --target-port 14550 \
  --loop true
```

## Hex Dump

To print packet payloads as hex while capturing:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpCaptureMain \
  --bind-address 0.0.0.0 \
  --port 14550 \
  --output mavlink.udp \
  --hex-dump true
```

To print packet payloads as hex while replaying:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpReplayMain \
  --input mavlink.udp \
  --target-address 127.0.0.1 \
  --target-port 14550 \
  --hex-dump true
```

## Example: MAVLink Capture and Replay

Capture traffic from a MAVLink source:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpCaptureMain \
  --bind-address 0.0.0.0 \
  --port 14550 \
  --output mavlink-capture.udp
```

Replay it to a local MAVLink consumer:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpReplayMain \
  --input mavlink-capture.udp \
  --target-address 127.0.0.1 \
  --target-port 14550
```

## Example: NMEA 0183 Stream Convert and Replay

Convert a raw NMEA 0183 style stream:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpStreamConvertMain \
  --input nmea-stream.raw \
  --output nmea-capture.udp
```

Replay the generated capture to a local UDP listener:

```bash
java -cp udp_helpers.jar io.mapsmessaging.tools.udphelpers.UdpReplayMain \
  --input nmea-capture.udp \
  --target-address 127.0.0.1 \
  --target-port 10110 \
  --preserve-timing false
```

## Notes

- Capture files are overwritten by default.
- Capture files are not plain packet dumps. They include metadata and packet boundaries.
- The recorded source address and source port are preserved in the file, but replay sends packets to the configured target address and port.
- `System.nanoTime()` timestamps are relative and only used for spacing packets during replay.
- The stream converter does not validate NMEA checksums. It only scans for `$` record boundaries.
- The format is intentionally simple and versioned so future converters can create compatible files from raw streams.
