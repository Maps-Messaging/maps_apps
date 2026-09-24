# MAPS Test Lab

MAPS Test Lab is a developer-only MCP server for controlling isolated MapsMessaging instances on a dedicated test host. It is intended for reproducing bridge, reconnect, subscription and routing failures without touching operational servers.

## Packaging

This module is built and tested by the normal Maven reactor, but it is deliberately excluded from the `maps-apps` Debian package. Package installation does **not** install, register, start or enable this server. There is no systemd unit and no package launcher.

Run it manually from the build output only.

## Ubuntu 24.04 test host bootstrap

For a fresh Ubuntu 24.04 Server host, run:

```bash
sudo ./maps_test_lab/scripts/bootstrap-ubuntu-24.04.sh
```

When the script is run through `sudo`, it configures Docker access for the invoking user. When running directly as root, specify the intended operator account:

```bash
MAPS_TEST_LAB_USER=matthew ./maps_test_lab/scripts/bootstrap-ubuntu-24.04.sh
```

The bootstrap installs:

- OpenJDK 21;
- Maven and Git;
- Docker Engine from Docker's official Ubuntu repository;
- Docker Buildx and Compose plugins;
- Mosquitto MQTT command-line clients;
- `jq`, `rsync`, `tcpdump`, `iproute2`, `procps`, `zip` and `unzip`;
- the `/srv/maps-test-lab` workspace.

It enables the Docker daemon but does not install, start or register MAPS Test Lab itself. Log out and back in after bootstrap so Docker group membership takes effect.

Membership of the `docker` group effectively grants root-level control of this dedicated test server. Do not add general-purpose or untrusted users to it.

## Build

From the repository root:

```bash
mvn clean verify
```

The shaded JAR is created under `maps_test_lab/target`.

## Configuration

Create a JSON configuration such as:

```json
{
  "root": "/srv/maps-test-lab",
  "bindAddress": "127.0.0.1",
  "port": 8091,
  "mqttPubCommand": "mosquitto_pub",
  "mqttSubCommand": "mosquitto_sub",
  "instances": {
    "drone": {
      "command": [
        "java",
        "-jar",
        "/opt/maps/maps.jar",
        "--config",
        "${configDir}"
      ],
      "environment": {},
      "mqttHost": "127.0.0.1",
      "mqttPort": 18831
    },
    "central": {
      "command": [
        "java",
        "-jar",
        "/opt/maps/maps.jar",
        "--config",
        "${configDir}"
      ],
      "environment": {},
      "mqttHost": "127.0.0.1",
      "mqttPort": 18832
    }
  }
}
```

Each instance receives its own workspace:

```text
/srv/maps-test-lab/
  instances/
    drone/
      config/
      data/
      logs/
    central/
      config/
      data/
      logs/
  evidence/
```

The command may use `${instance}`, `${instanceDir}`, `${configDir}`, `${dataDir}` and `${logFile}` placeholders.

Populate each instance's `config` directory before starting it. The lab does not generate MapsMessaging configuration because test topology is deliberately explicit.

## Run

```bash
java -jar maps_test_lab/target/maps_test_lab-1.0.0-SNAPSHOT.jar --config /srv/maps-test-lab/lab.json
```

The MCP endpoint is:

```text
http://127.0.0.1:8091/mcp
```

Bind to a non-loopback address only on an isolated test network. The server can start and stop processes and can publish arbitrary MQTT payloads to configured test brokers, so exposing it to an operational network would be a creative but poor security experiment.

## MCP tools

- `list_instances`
- `start_instance`
- `stop_instance`
- `restart_instance`
- `instance_status`
- `instance_logs`
- `instance_config`
- `mqtt_publish`
- `mqtt_subscribe`
- `create_evidence`

Process commands are taken only from the lab configuration. MCP callers cannot supply arbitrary executable commands.

`instance_config` is restricted to the instance configuration directory and rejects path traversal.

MQTT operations use `mosquitto_pub` and `mosquitto_sub` by default. Override their command paths in `lab.json` if required.

## Evidence

`create_evidence` creates a timestamped directory below `evidence/` containing the instance config, logs and a JSON manifest with process state. This is intended to preserve a failed run before restarting or changing configuration.

## Initial bridge-recovery workflow

A useful first scenario is:

1. start `central`;
2. start `drone`;
3. publish a unique test payload to the drone broker;
4. subscribe for it on central;
5. restart central;
6. repeat the publish/subscribe check;
7. restart drone;
8. repeat the check;
9. capture evidence if any stage fails.

Higher-level scenario orchestration can be layered on top of these MCP primitives without adding unrestricted shell execution to the MCP surface.
