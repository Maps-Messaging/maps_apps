# maps_apps

Standalone CLI tools for the [MapsMessaging](https://mapsmessaging.io) platform.

## Modules

### [mqtt_logger](mqtt_logger/README.md)

Subscribes to a MAPS MQTT v5 broker and logs message envelope fields to hourly-rotating
CSV or NDJSON files. Deployable as a systemd service (Raspberry Pi, Linux) or Docker
sidecar. See [mqtt_logger/INSTALL.md](mqtt_logger/INSTALL.md) for deployment instructions.

### [audit_viewer](audit_viewer/README.md)

Reads and verifies tamper-evident JSONL audit journals produced by MAPS, checking
hash-chain integrity and optional Ed25519 signature verification.

## Build

```bash
mvn clean package
```

Shaded JARs land in `<module>/target/`.
