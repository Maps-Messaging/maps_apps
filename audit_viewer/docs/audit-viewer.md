# MAPS Audit Viewer

The MAPS Audit Viewer reads append-only MAPS audit journals, verifies their hash chain and optional signatures, and presents records for operational review and incident investigation.

## Installation

The `maps-apps` Debian package installs `maps-audit-viewer` and its manual page.

```bash
man maps-audit-viewer
```

## Use

Run the viewer against the audit journal or directory described by `maps-audit-viewer --help`. The journal remains the source of truth; the viewer is a presentation and verification tool.

Audit records are intended for meaningful state transitions, security-sensitive actions, configuration changes and externally significant decisions rather than high-frequency tracing. See the module README for audit design, correlation and operational guidance.
