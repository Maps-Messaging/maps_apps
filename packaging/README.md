# Debian packaging

The `maps-apps` Debian package contains the shaded JAR produced by every Maven module declared in the root `pom.xml`.

## Installed files

Application JARs are installed under:

```text
/opt/maps/apps
```

A launcher is generated for each Maven module under `/usr/bin`. Module underscores are converted to hyphens. Examples:

```text
mqtt_logger   -> /usr/bin/maps-mqtt-logger
canbus_replay -> /usr/bin/maps-canbus-replay
udp_helpers   -> /usr/bin/maps-udp-helpers
```

The package depends on the `maps` Debian package.

## Build

Build the Maven modules first:

```bash
mvn clean verify
```

Then build the Debian package:

```bash
./packaging/scripts/build-debian.sh
```

The resulting package is written to:

```text
packaging/output/maps-apps_<version>_all.deb
```

Maven snapshot versions are converted from `1.0.0-SNAPSHOT` to the Debian-compatible `1.0.0~snapshot` form.

## Requirements

- Maven
- `dpkg-deb`
- completed module builds under each module's `target` directory

The packaging script does not invoke the Maven build. This keeps Java compilation and Debian assembly as separate stages.
