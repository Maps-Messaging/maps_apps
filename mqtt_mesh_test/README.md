# Maps MQTT mesh test

Replay recorded MQTT events from MAPS logger NDJSON logs, optionally validating delivery at any number of MQTT observation URLs. The first workload is the REPMUS archive; the test is independent of that dataset.

```bash
# Original replay-only behaviour (no subscribers)
maps-mqtt-mesh-test --dir /data/repmus --broker tcp://s1:1883 --speed 100

# Independently observe ingress, intermediate and egress brokers
maps-mqtt-mesh-test --dir /data/repmus --broker tcp://s1:1883 --speed 100 \
  --session-prefix /main_folder/sub_folder \
  --observe tcp://s1:1883 --observe tcp://s2:1883 --observe tcp://s3:1883 \
  --observe-topic '/#' --drain-ms 15000 --report results.json
```

Both `maps-mqtt-mesh-test` and the backwards-compatible `maps-mqtt-replay` launcher use the same shaded JAR and options.

## Options

- `--dir`: Directory of timestamp-ordered `.ndjson` and `.ndjson.gz` files (scanned recursively, streaming merge). The parallel startup scan counts records and reports the largest valid MQTT 3.1.1 PUBLISH packet, including topic, file and line number, before publishing begins. Packet size includes the MQTT fixed header, encoded remaining length, UTF-8 topic, QoS packet identifier and payload. The scan decodes each record once and may take longer than counting lines alone.
- `--broker`: MQTT 3.1.1 publishing URL.
- `--speed`: Replay factor, default `1.0`; existing timing and publish-session behaviour is unchanged.
- `--no-delay`: Publish events back-to-back, with no intentional timestamp-based waits. Overrides timing from `--speed` if both are supplied; MQTT publication itself can still block.
- `--session-per-topic`: One publishing session per complete topic.
- `--session-prefix`: One publishing session per immediate child beneath the configured path. Mutually exclusive with `--session-per-topic`.
- `--session-idle-ms`: Close idle publishing connections after this many milliseconds (default `60000`); the session is recreated on its next event. The reported session count is the number of currently open connections.
- `--qos`: Default QoS if absent from the recording, default `1`.
- `--observe URL`: Repeatable. With no occurrences, replay-only mode has no validation overhead.
- `--observe-topic`: One MQTT topic filter used on every observation point, default `#`. Use a filter that covers **every** published topic to avoid false missing-message results.
- `--drain-ms`: Additional receive time after the last publication, default `5000` ms.
- `--report`: Optional path for a pretty-printed JSON validation report; reports also go to stdout.

Each observer connects and completes its subscription before the first replay event is published. Successful subscriptions are logged, and every 10,000 published events the progress line includes each observer's cumulative received and matched counts. These live counters do not perform final missing-message validation; that still occurs after the drain period. The replayed topic and payload are matched using SHA-256 and occurrence counts; no ordering assumptions are made across topics. Matching is exact at the reconstructed MQTT payload-byte level. Reports include the total published and, per observer: received, matched, missing, unexpected (all unmatched received events), and connection loss errors. Matched occurrences are discarded immediately, so only unmatched topic/payload-hash keys remain in memory. The legacy `excess` report field remains for compatibility but is always zero: identifying a surplus copy of a previously matched key separately from an entirely unknown key would require retaining historical keys. Mismatch examples show remaining unmatched counts, not historical totals. Reports include up to 100 mismatch examples per observer and the number of outstanding mismatch keys. Any validation failure exits non-zero.

**Limitations:** MQTT 3.1.1 subscriptions cannot suppress retained messages, so stale retained messages or unrelated traffic can produce unexpected results. No event correlation ID is injected. Identical payloads on the same topic cannot be individually distinguished; counts can hide one loss offset by one duplicate. Memory use grows with outstanding unmatched topic/payload keys, not all successfully matched keys. Large delivery backlogs or unrelated traffic may still require memory profiling. Snapshotting occurs after the drain period; late arrivals count as missing. The tool does not determine topology, individual message routes or MAVLink command equivalence.

The original logger JSON conversion limitation remains: arbitrary binary payloads, original JSON whitespace, MQTT v5 properties and upstream client IDs are not recoverable from the recorded JSON alone. Use an isolated broker/test mesh before replaying operational commands or retained state.
