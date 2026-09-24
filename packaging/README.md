# Debian packaging

The `maps-apps` Debian package contains deployable JARs from package-enabled Maven modules declared in the root `pom.xml`. Developer-only modules are explicitly excluded by the packaging script.

## Developer-only exclusions

`maps_test_lab` is built and tested by Maven but is deliberately not installed by the Debian package. It has no package launcher or systemd unit and is never enabled or started by package installation.

## Installed files

Application JARs are installed under:

```text
/opt/maps/apps
```

Commands supplied by each module's `install/bin` directory are installed under `/usr/bin`. Modules without explicit launchers receive a generated `maps-<module>` launcher.

Manual pages supplied under either `<module>/install/man/man<section>` or `packaging/install/man/man<section>` are compressed and installed below `/usr/share/man`.

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
- `gzip`
- completed module builds under each module's `target` directory

The packaging script does not invoke the Maven build. Java compilation and Debian assembly remain separate pipeline stages.
