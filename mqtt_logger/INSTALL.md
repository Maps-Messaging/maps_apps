# Maps Logger Installation

This guide installs and configures the Maps Logger service.

The logger subscribes to the configured MAPS/MQTT topic and writes received records to:

```text
/opt/logger/maps-logger/maps-values.csv
```

## Important: enable JSON transformation

For valid logging, the MAPS server must transform MQTT messages into JSON before the logger receives them.

Edit:

```text
/opt/maps/conf/TransformationManager.yaml
```

Add the following configuration:

```yaml
transformations:
  data:
    - pattern: "*://*/*/*"
      transformation: ""

    - pattern: "tcp://127.0.0.1/mqtt/*"
      transformation: "Schema-To-Json"
```

If this is not configured, the logger will not receive the structured JSON fields required for valid logging, including timestamps and related metadata.

## Package contents

The installation package should contain these files:

```text
install.sh
maps-logger.service
maps-logger.env
mqtt-logger-1.0.0-SNAPSHOT.jar
```

## Installation

Extract the package:

```bash
tar -xvf maps-logger.tar
```

Change into the scripts directory:

```bash
cd ./scripts
```

Run the installer:

```bash
./install.sh
```

The installer will:

- create the required `/opt/logger` directory structure
- install the logger JAR
- create the `/opt/logger/bin/maps-logger` launcher script
- install the systemd service
- install the logger environment file
- enable and start the `maps-logger` service

## Installed layout

After installation, the logger is installed under:

```text
/opt/logger/bin/maps-logger
/opt/logger/conf/maps-logger.env
/opt/logger/lib/maps-logger.jar
/opt/logger/maps-logger/maps-values.csv
/opt/logger/systemd/maps-logger.service
```

The active systemd service file is copied to:

```text
/etc/systemd/system/maps-logger.service
```

## Configuration

The logger configuration is stored in:

```text
/opt/logger/conf/maps-logger.env
```

Example configuration:

```bash
MAPS_LOGGER_URL=tcp://localhost:1883
MAPS_LOGGER_TOPIC=/#
MAPS_LOGGER_QOS=1
MAPS_LOGGER_FORMAT=csv
MAPS_LOGGER_OUTPUT=/opt/logger/maps-logger/maps-values.csv
```

## Service management

Check the service status:

```bash
systemctl status maps-logger --no-pager
```

View recent logs:

```bash
journalctl -u maps-logger -n 100 --no-pager
```

Follow the logs:

```bash
journalctl -u maps-logger -f
```

Restart the service:

```bash
sudo systemctl restart maps-logger
```

Stop the service:

```bash
sudo systemctl stop maps-logger
```

## Output

The default CSV output file is:

```text
/opt/logger/maps-logger/maps-values.csv
```

To change the output file, edit:

```text
/opt/logger/conf/maps-logger.env
```

Then restart the service:

```bash
sudo systemctl restart maps-logger
```

## Troubleshooting

### No timestamps or missing metadata

Check that `/opt/maps/conf/TransformationManager.yaml` contains the required `Schema-To-Json` transformation.

Without this transformation, the logger will not receive valid structured JSON records.

### Service does not start

Check the service status:

```bash
systemctl status maps-logger --no-pager
```

Then check the logs:

```bash
journalctl -u maps-logger -n 100 --no-pager
```

### Java cannot run the logger

The installed launcher runs the main class directly using:

```bash
java -cp /opt/logger/lib/maps-logger.jar io.mapsmessaging.tools.valuelogger.MapsValueLoggerMain
```

This avoids requiring a `Main-Class` manifest entry in the JAR.
