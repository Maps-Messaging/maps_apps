## Debian package

After building the Maven modules, create the `maps-apps` Debian package with:

```bash
./packaging/scripts/build-debian.sh
```

The package installs the applications under `/opt/maps/apps`, creates command launchers under `/usr/bin`, and depends on the `maps` package.
