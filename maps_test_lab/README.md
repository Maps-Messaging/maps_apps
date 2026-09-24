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

The bootstrap installs OpenJDK 21, Maven, Git, Docker Engine from Docker's official Ubuntu repository, Docker Buildx and Compose, Mosquitto MQTT clients, and common diagnostic tools including `jq`, `rsync`, `tcpdump`, `iproute2`, `procps`, `zip` and `unzip`.

It also creates `/srv/maps-test-lab`. It enables Docker but does not install, start or register MAPS Test Lab itself. Log out and back in after bootstrap so Docker group membership takes effect.

Membership of the `docker` group effectively grants root-level control of this dedicated test server. Do not add general-purpose or untrusted users to it.

## Build

From the repository root:

```bash
mvn clean verify
```

The shaded JAR is created under `maps_test_lab/target`.

## Docker configuration

Docker is the preferred provider for multi-server test topologies.

Example two-server lab:

```json
{
  "root": "/srv/maps-test-lab",
  "bindAddress": "127.0.0.1",
  "port": 8091,
  "dockerCommand": "docker",
  "mqttPubCommand": "mosquitto_pub",
  "mqttSubCommand": "mosquitto_sub",
  "instances": {
    "ralf-a": {
      "provider": "docker",
      "image": "mapsmessaging/server:test",
      "network": "ralf-lab",
      "ports": [
        "18831:1883"
      ],
      "mqttHost": "127.0.0.1",
      "mqttPort": 18831,
      "debugPort": 5005,
      "debugSuspend": false,
      "environment": {},
      "containerCommand": [
        "--config",
        "/opt/maps/config"
      ]
    },
    "ralf-b": {
      "provider": "docker",
      "image": "mapsmessaging/server:test",
      "network": "ralf-lab",
      "ports": [
        "18832:1883"
      ],
      "mqttHost": "127.0.0.1",
      "mqttPort": 18832,
      "debugPort": 5006,
      "debugSuspend": false,
      "environment": {},
      "containerCommand": [
        "--config",
        "/opt/maps/config"
      ]
    }
  }
}
```

The lab creates the named Docker network when necessary and mounts:

```text
<instance>/config -> /opt/maps/config
<instance>/data   -> /opt/maps_data
```

Additional application ports can be supplied through `ports` using normal Docker `host:container` notation.

The image and `containerCommand` are intentionally configurable. The test lab does not assume a particular MapsMessaging image layout.

## IntelliJ remote debugging

Set `debugPort` on a Docker instance to enable JDWP. The lab publishes the same host/container port and adds the equivalent of:

```text
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

through `JAVA_TOOL_OPTIONS`.

Set `debugSuspend: true` when the Maps JVM should wait for the debugger before continuing.

In IntelliJ IDEA create a **Remote JVM Debug** configuration and use:

```text
Host: <test-server-address>
Port: 5005
```

Use the configured port for the target instance, for example `5006` for `ralf-b`.

JDWP has no meaningful authentication. Expose debug ports only on the isolated test network or restrict them with the host firewall/SSH tunnelling. Humanity has already produced enough unauthenticated management ports.

## Process provider

The original process provider remains available for tests that do not need Docker:

```json
{
  "instances": {
    "local": {
      "provider": "process",
      "command": [
        "java",
        "-jar",
        "/opt/maps/maps.jar",
        "--config",
        "${configDir}"
      ],
      "mqttHost": "127.0.0.1",
      "mqttPort": 1883
    }
  }
}
```

Process commands may use `${instance}`, `${instanceDir}`, `${configDir}`, `${dataDir}` and `${logFile}` placeholders.

## Instance workspace

Each instance receives its own workspace:

```text
/srv/maps-test-lab/
  instances/
    ralf-a/
      config/
      data/
      logs/
    ralf-b/
      config/
      data/
      logs/
  evidence/
```

## Supplying configurations through MCP

The `write_instance_config` tool creates files only below an instance's `config` directory.

It accepts UTF-8 text by default and Base64 for binary configuration resources. Existing files are preserved unless `overwrite: true` is explicitly supplied.

This allows a supplied server configuration to be recreated without granting the MCP caller arbitrary filesystem access.

`instance_config` lists or reads configuration files for verification.

## Run

```bash
java -jar maps_test_lab/target/maps_test_lab-1.0.0-SNAPSHOT.jar --config /srv/maps-test-lab/lab.json
```

The MCP endpoint is:

```text
http://127.0.0.1:8091/mcp
```

Bind to a non-loopback address only on an isolated test network. The server can start and stop processes/containers and publish arbitrary MQTT payloads to configured test brokers.

## MCP tools

- `list_instances`
- `start_instance`
- `stop_instance`
- `restart_instance`
- `instance_status`
- `instance_logs`
- `list_log_files`
- `read_log`
- `instance_config`
- `write_instance_config`
- `mqtt_publish`
- `mqtt_subscribe`
- `create_evidence`
- `create_evidence_archive`

MCP callers cannot supply arbitrary executable commands. Process commands, Docker images, networks and container commands come from `lab.json`.

MQTT operations use `mosquitto_pub` and `mosquitto_sub` by default.

## Logs and analysis

`instance_logs` returns a bounded tail of the current process or Docker stdout/stderr.

`list_log_files` lists persisted files under the instance `logs` directory.

`read_log` reads a bounded chunk of a selected log file using byte offsets. Responses include `nextOffset` and `eof`, allowing large logs to be analysed incrementally without stuffing hundreds of megabytes into one MCP response.

For Docker instances, Docker stdout/stderr is captured into `logs/docker.log` when evidence or persisted-log operations are requested.

## Evidence and download bundles

`create_evidence` creates a timestamped directory containing:

- the instance configuration;
- available persisted logs;
- Docker stdout/stderr where applicable;
- a JSON status manifest.

`create_evidence_archive` additionally creates a ZIP in `/srv/maps-test-lab/evidence` and returns its server path and size. The ZIP can then be copied from the isolated host using the operator's normal SSH/SCP/rsync mechanism.

## Example supplied-config workflow

Given two real MapsMessaging configurations, an MCP client can:

1. write the first configuration into `ralf-a/config`;
2. write the second configuration into `ralf-b/config`;
3. start both Docker instances;
4. verify process/container and bridge state from logs;
5. publish a controlled MQTT message into the first server;
6. verify delivery from the second server;
7. restart either server;
8. repeat the delivery check;
9. inspect logs incrementally;
10. create an evidence ZIP when a failure occurs.

The same model scales to longer chains by declaring additional Docker instances on the test network.
