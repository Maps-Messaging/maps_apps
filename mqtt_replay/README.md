# Maps MQTT replay

Publish recorded MQTT events from MAPS logger NDJSON logs. Accepts .ndjson and .ndjson.gz files recursively. A streaming timestamp merge avoids holding the month of REPMUS records in memory; each file must be internally timestamp ordered.

```bash
maps-mqtt-replay --dir /data/repmus --broker tcp://127.0.0.1:1883
maps-mqtt-replay --dir /data/repmus --broker tcp://127.0.0.1:1883 --speed 100
maps-mqtt-replay --dir /data/repmus --broker tcp://127.0.0.1:1883 --speed 100 --session-per-topic
```

- `--speed` defaults to 1.0, using logger receive timestamps where present. Speed scales elapsed time; processing and network capacity can limit effective throughput.
- `--session-per-topic` defaults to off. When enabled, one separate MQTT 3.1.1 clean-session client is opened per distinct topic and retained until replay finishes. This can use substantial broker connection resources.
- `--qos` sets the fallback MQTT QoS, default 1. Recorded QoS values override it; recorded retain flags are used where present.
- Invalid entries fail replay with a filename and line number. No records are intentionally dropped.
- Input must include topic, payload and a timestamp. The transmitted MQTT payload is the JSON `payload` value, not the entire logger envelope. JSON string payloads are sent as plain UTF-8 text.
- Arbitrary binary payloads, original JSON whitespace, MQTT v5 properties and upstream client/session IDs cannot be recovered from the JSON representation alone. The replay is **not** a byte-identical reconstruction of every original MQTT publication.
- It does not independently verify server consumption or regenerated MAVLink commands. Use an isolated test broker because replayed retained messages and commands can affect connected systems.

The root Maven build assembles a shaded mqtt_replay JAR. Debian packaging automatically picks up the new module and installs `maps-mqtt-replay`.
