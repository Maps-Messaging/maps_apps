#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

PACKAGE_NAME="maps-apps"
INSTALL_DIR="/opt/maps/apps"
OUTPUT_DIR="${ROOT_DIR}/packaging/output"
WORK_DIR="${ROOT_DIR}/packaging/work"

LOGGER_MODULE="mqtt_logger"
LOGGER_INSTALL_SOURCE="${ROOT_DIR}/${LOGGER_MODULE}/install"
LOGGER_SYSTEMD_SOURCE="${LOGGER_INSTALL_SOURCE}/systemd"
LOGGER_CONFIG_DIR="/etc/maps-logger"
LOGGER_LOG_DIR="/var/log/maps-logger"
SYSTEMD_DIR="/lib/systemd/system"

command -v dpkg-deb >/dev/null 2>&1 || {
  echo "dpkg-deb is required" >&2
  exit 1
}

command -v mvn >/dev/null 2>&1 || {
  echo "mvn is required" >&2
  exit 1
}

MAVEN_VERSION="$(
  mvn \
    --quiet \
    --non-recursive \
    -Dstyle.color=never \
    help:evaluate \
    -Dexpression=project.version \
    -DforceStdout
)"

if [[ -z "${MAVEN_VERSION}" ]]; then
  echo "Unable to determine Maven project version" >&2
  exit 1
fi

DEBIAN_VERSION="${MAVEN_VERSION//-SNAPSHOT/~snapshot}"
ARCHITECTURE="all"

PACKAGE_ROOT="${WORK_DIR}/${PACKAGE_NAME}_${DEBIAN_VERSION}_${ARCHITECTURE}"
PACKAGE_INSTALL_DIR="${PACKAGE_ROOT}${INSTALL_DIR}"
PACKAGE_BIN_DIR="${PACKAGE_ROOT}/usr/bin"
PACKAGE_CONTROL_DIR="${PACKAGE_ROOT}/DEBIAN"

rm -rf "${PACKAGE_ROOT}"

mkdir -p \
  "${PACKAGE_CONTROL_DIR}" \
  "${PACKAGE_INSTALL_DIR}" \
  "${PACKAGE_BIN_DIR}" \
  "${OUTPUT_DIR}"

mapfile -t MODULES < <(
  sed -n \
    's:.*<module>\([^<]*\)</module>.*:\1:p' \
    "${ROOT_DIR}/pom.xml"
)

if [[ ${#MODULES[@]} -eq 0 ]]; then
  echo "No Maven modules found in ${ROOT_DIR}/pom.xml" >&2
  exit 1
fi

find_module_jar() {
  local module="$1"
  local target_dir="${ROOT_DIR}/${module}/target"
  local jars=()

  if [[ ! -d "${target_dir}" ]]; then
    echo "Missing target directory for module ${module}: ${target_dir}" >&2
    echo "Run Maven before building the Debian package." >&2
    exit 1
  fi

  mapfile -t jars < <(
    find "${target_dir}" \
      -maxdepth 1 \
      -type f \
      -name '*.jar' \
      ! -name 'original-*.jar' \
      ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' \
      ! -name '*-tests.jar' \
      -printf '%T@ %p\n' |
      sort -nr |
      cut -d' ' -f2-
  )

  if [[ ${#jars[@]} -eq 0 ]]; then
    echo "No deployable JAR found for module ${module} in ${target_dir}" >&2
    exit 1
  fi

  printf '%s\n' "${jars[0]}"
}

install_cli_module() {
  local module="$1"
  local jar_file="$2"
  local installed_name="${module}.jar"
  local command_name="maps-${module//_/-}"

  install \
    -m 0644 \
    "${jar_file}" \
    "${PACKAGE_INSTALL_DIR}/${installed_name}"

  cat > "${PACKAGE_BIN_DIR}/${command_name}" <<EOF
#!/bin/sh
exec java -jar "${INSTALL_DIR}/${installed_name}" "\$@"
EOF

  chmod 0755 "${PACKAGE_BIN_DIR}/${command_name}"

  echo "Included CLI module ${module}: $(basename "${jar_file}")"
}

install_logger_module() {
  local jar_file="$1"

  install \
    -m 0644 \
    "${jar_file}" \
    "${PACKAGE_INSTALL_DIR}/${LOGGER_MODULE}.jar"

  echo "Included service module ${LOGGER_MODULE}: $(basename "${jar_file}")"
}

validate_logger_resources() {
  local required_files=(
    "${LOGGER_INSTALL_SOURCE}/install.sh"
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger.env"
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger.service"
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger-compress.service"
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger-compress.timer"
  )

  local file

  for file in "${required_files[@]}"; do
    if [[ ! -f "${file}" ]]; then
      echo "Missing MQTT logger installation resource: ${file}" >&2
      exit 1
    fi
  done
}

install_logger_resources() {
  validate_logger_resources

  mkdir -p \
    "${PACKAGE_ROOT}${LOGGER_CONFIG_DIR}" \
    "${PACKAGE_ROOT}${LOGGER_LOG_DIR}" \
    "${PACKAGE_ROOT}${SYSTEMD_DIR}"

  install \
    -m 0644 \
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger.env" \
    "${PACKAGE_ROOT}${LOGGER_CONFIG_DIR}/env"

  sed \
    "s#/usr/lib/maps-logger/maps-value-logger.jar#${INSTALL_DIR}/${LOGGER_MODULE}.jar#g" \
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger.service" \
    > "${PACKAGE_ROOT}${SYSTEMD_DIR}/maps-logger.service"

  chmod 0644 \
    "${PACKAGE_ROOT}${SYSTEMD_DIR}/maps-logger.service"

  install \
    -m 0644 \
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger-compress.service" \
    "${PACKAGE_ROOT}${SYSTEMD_DIR}/maps-logger-compress.service"

  install \
    -m 0644 \
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger-compress.timer" \
    "${PACKAGE_ROOT}${SYSTEMD_DIR}/maps-logger-compress.timer"

  cat > "${PACKAGE_CONTROL_DIR}/conffiles" <<EOF
${LOGGER_CONFIG_DIR}/env
EOF

  echo "Included MQTT logger systemd units and configuration"
}

for module in "${MODULES[@]}"; do
  jar_file="$(find_module_jar "${module}")"

  if [[ "${module}" == "${LOGGER_MODULE}" ]]; then
    install_logger_module "${jar_file}"
  else
    install_cli_module "${module}" "${jar_file}"
  fi
done

install_logger_resources

cat > "${PACKAGE_CONTROL_DIR}/control" <<EOF
Package: ${PACKAGE_NAME}
Version: ${DEBIAN_VERSION}
Section: utils
Priority: optional
Architecture: ${ARCHITECTURE}
Depends: maps, systemd
Maintainer: MapsMessaging B.V. <matthew.buckton@mapsmessaging.io>
Homepage: https://www.mapsmessaging.io
Description: MapsMessaging command-line applications
 Collection of operational and diagnostic applications for MapsMessaging,
 installed under ${INSTALL_DIR}.
 .
 Includes the MAPS MQTT value logger systemd service and compression timer.
EOF

cat > "${PACKAGE_CONTROL_DIR}/postinst" <<'EOF'
#!/bin/sh
set -e

if [ "$1" = "configure" ] && [ -d /run/systemd/system ]; then
  systemctl daemon-reload >/dev/null 2>&1 || true

  systemctl enable maps-logger.service >/dev/null 2>&1 || true
  systemctl enable maps-logger-compress.timer >/dev/null 2>&1 || true
  systemctl start maps-logger-compress.timer >/dev/null 2>&1 || true

  if systemctl is-active --quiet maps-logger.service; then
    systemctl restart maps-logger.service
  fi
fi

exit 0
EOF

cat > "${PACKAGE_CONTROL_DIR}/prerm" <<'EOF'
#!/bin/sh
set -e

if [ "$1" = "remove" ] && [ -d /run/systemd/system ]; then
  systemctl stop maps-logger.service >/dev/null 2>&1 || true
  systemctl stop maps-logger-compress.timer >/dev/null 2>&1 || true

  systemctl disable maps-logger.service >/dev/null 2>&1 || true
  systemctl disable maps-logger-compress.timer >/dev/null 2>&1 || true
fi

exit 0
EOF

cat > "${PACKAGE_CONTROL_DIR}/postrm" <<'EOF'
#!/bin/sh
set -e

if [ -d /run/systemd/system ]; then
  systemctl daemon-reload >/dev/null 2>&1 || true
fi

exit 0
EOF

chmod 0755 \
  "${PACKAGE_CONTROL_DIR}/postinst" \
  "${PACKAGE_CONTROL_DIR}/prerm" \
  "${PACKAGE_CONTROL_DIR}/postrm"

OUTPUT_FILE="${OUTPUT_DIR}/${PACKAGE_NAME}_${DEBIAN_VERSION}_${ARCHITECTURE}.deb"

rm -f "${OUTPUT_FILE}"

dpkg-deb \
  --root-owner-group \
  --build \
  "${PACKAGE_ROOT}" \
  "${OUTPUT_FILE}"

echo
echo "Built ${OUTPUT_FILE}"
echo
echo "Installed applications:"
find "${PACKAGE_INSTALL_DIR}" -maxdepth 1 -type f -printf '  %f\n' | sort

echo
echo "Installed commands:"
find "${PACKAGE_BIN_DIR}" -maxdepth 1 -type f -printf '  %f\n' | sort

echo
echo "Installed systemd units:"
find "${PACKAGE_ROOT}${SYSTEMD_DIR}" -maxdepth 1 -type f -printf '  %f\n' | sort