#!/usr/bin/env bash

set -e

INSTALL_ROOT="/opt/logger"
SERVICE_NAME="maps-logger"
JAR_SOURCE_NAME="mqtt_logger-1.0.0-SNAPSHOT.jar"
JAR_INSTALL_NAME="maps-logger.jar"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

sudo mkdir -p "${INSTALL_ROOT}/bin"
sudo mkdir -p "${INSTALL_ROOT}/conf"
sudo mkdir -p "${INSTALL_ROOT}/lib"
sudo mkdir -p "${INSTALL_ROOT}/maps-logger"
sudo mkdir -p "${INSTALL_ROOT}/systemd"

sudo cp "${SCRIPT_DIR}/maps-logger.env" "${INSTALL_ROOT}/conf/maps-logger.env"
sudo chmod 644 "${INSTALL_ROOT}/conf/maps-logger.env"

sudo cp "${SCRIPT_DIR}/maps-logger.service" "${INSTALL_ROOT}/systemd/maps-logger.service"
sudo chmod 644 "${INSTALL_ROOT}/systemd/maps-logger.service"

sudo cp "${SCRIPT_DIR}/${JAR_SOURCE_NAME}" "${INSTALL_ROOT}/lib/${JAR_INSTALL_NAME}"
sudo chmod 644 "${INSTALL_ROOT}/lib/${JAR_INSTALL_NAME}"

sudo tee "${INSTALL_ROOT}/bin/maps-logger" >/dev/null <<'EOF'
#!/usr/bin/env bash

exec java -cp /opt/logger/lib/maps-logger.jar io.mapsmessaging.tools.valuelogger.MapsValueLoggerMain "$@"
EOF

sudo chmod 755 "${INSTALL_ROOT}/bin/maps-logger"

sudo cp "${INSTALL_ROOT}/systemd/maps-logger.service" "/etc/systemd/system/maps-logger.service"
sudo chmod 644 "/etc/systemd/system/maps-logger.service"

sudo systemctl daemon-reload
sudo systemctl enable "${SERVICE_NAME}"
sudo systemctl restart "${SERVICE_NAME}"

sudo systemctl status "${SERVICE_NAME}" --no-pager