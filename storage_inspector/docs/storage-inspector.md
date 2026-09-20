# MAPS Storage Inspector

The MAPS Storage Inspector is a read-only offline diagnostic and export tool for MAPS dynamic storage. It validates partition/index structure, can decode stored messages using a matching MAPS server dependency, and can export active events as NDJSON for later analysis.

## Installation

The `maps-apps` Debian package installs the command as `maps-storage-inspector`. Use `man maps-storage-inspector` for the installed command reference.

## Typical use

```bash
maps-storage-inspector --input /srv/maps-snapshot --report /tmp/storage-report.ndjson
```

Decode stored messages without exporting them:

```bash
maps-storage-inspector --input /srv/maps-snapshot --decode --report /tmp/storage-report.ndjson
```

Export active events:

```bash
maps-storage-inspector --input /srv/maps-snapshot --output /tmp/storage-events.ndjson --report /tmp/storage-report.ndjson
```

The inspector is deliberately read-only. Use a stopped-server copy or filesystem snapshot when investigating possible corruption. See the module README for storage format details, filtering, archive handling, decoder version selection, limits and validation.
