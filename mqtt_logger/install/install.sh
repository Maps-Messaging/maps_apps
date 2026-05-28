#!/bin/bash
set -euo pipefail

INSTALL_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_DIR="/etc/systemd/system"
CONF_DIR="/etc/maps-logger"
LIB_DIR="/usr/lib/maps-logger"
LOG_DIR="/var/log/maps-logger"

# ---- Checks ----------------------------------------------------------------

if [[ $EUID -ne 0 ]]; then
  echo "Error: install.sh must be run as root (use sudo)" >&2
  exit 1
fi

check_java() {
  if ! command -v java &>/dev/null; then
    echo "Error: java not found. Install Java 21 or later and ensure it is on PATH." >&2
    exit 1
  fi

  local version
  version=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')

  if [[ "$version" -lt 21 ]] 2>/dev/null; then
    echo "Error: Java 21 or later required. Found Java $version." >&2
    exit 1
  fi
}

check_java

JAR_FILE=$(ls "$INSTALL_DIR"/mqtt_logger-*.jar 2>/dev/null | head -1)
if [[ -z "$JAR_FILE" ]]; then
  echo "Error: no mqtt_logger-*.jar found in $INSTALL_DIR" >&2
  exit 1
fi

# ---- Install ---------------------------------------------------------------

echo "Creating directories..."
mkdir -p "$LIB_DIR" "$CONF_DIR" "$LOG_DIR"

echo "Installing JAR: $(basename "$JAR_FILE")"
cp "$JAR_FILE" "$LIB_DIR/maps-value-logger.jar"

echo "Installing systemd units..."
cp "$INSTALL_DIR/systemd/maps-logger.service"          "$SERVICE_DIR/"
cp "$INSTALL_DIR/systemd/maps-logger-compress.service" "$SERVICE_DIR/"
cp "$INSTALL_DIR/systemd/maps-logger-compress.timer"   "$SERVICE_DIR/"

echo "Installing environment file..."
if [[ ! -f "$CONF_DIR/env" ]]; then
  cp "$INSTALL_DIR/systemd/maps-logger.env" "$CONF_DIR/env"
  echo "  Created $CONF_DIR/env"
else
  echo "  $CONF_DIR/env already exists — not overwritten (existing config preserved)"
fi

echo "Enabling services..."
systemctl daemon-reload
systemctl enable maps-logger
systemctl enable maps-logger-compress.timer
systemctl start  maps-logger-compress.timer

if systemctl is-active --quiet maps-logger; then
  echo "Restarting maps-logger to pick up new JAR..."
  systemctl restart maps-logger
fi

echo ""
echo "Installation complete."
echo ""
echo "Next steps:"
echo "  1. Add the MAPS server transformation config from:"
echo "       $INSTALL_DIR/maps-config-snippet.yaml"
echo "  2. Edit $CONF_DIR/env to set MAPS_URL and other settings."
echo "  3. Start the logger: systemctl start maps-logger"
echo "  4. Check status:     systemctl status maps-logger"
echo "  5. View logs:        journalctl -u maps-logger -f"
