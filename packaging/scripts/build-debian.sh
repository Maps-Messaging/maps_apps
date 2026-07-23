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
MAN_DIR="/usr/share/man"

for required_command in dpkg-deb mvn gzip; do
  command -v "${required_command}" >/dev/null 2>&1 || {
    echo "${required_command} is required" >&2
    exit 1
  }
done

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
PACKAGE_MAN_DIR="${PACKAGE_ROOT}${MAN_DIR}"

rm -rf "${PACKAGE_ROOT}"
mkdir -p \
  "${PACKAGE_CONTROL_DIR}" \
  "${PACKAGE_INSTALL_DIR}" \
  "${PACKAGE_BIN_DIR}" \
  "${PACKAGE_MAN_DIR}" \
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

install_module_jar() {
  local module="$1"
  local jar_file="$2"

  install \
    -m 0644 \
    "${jar_file}" \
    "${PACKAGE_INSTALL_DIR}/${module}.jar"

  echo "Included module ${module}: $(basename "${jar_file}")"
}

install_default_launcher() {
  local module="$1"
  local command_name="maps-${module//_/-}"

  cat > "${PACKAGE_BIN_DIR}/${command_name}" <<EOF_LAUNCHER
#!/bin/sh
exec java -jar "${INSTALL_DIR}/${module}.jar" "\$@"
EOF_LAUNCHER

  chmod 0755 "${PACKAGE_BIN_DIR}/${command_name}"
  echo "Included CLI command ${command_name}"
}

install_module_launchers() {
  local module="$1"
  local launcher_dir="${ROOT_DIR}/${module}/install/bin"
  local launcher
  local command_name

  if [[ ! -d "${launcher_dir}" ]]; then
    return
  fi

  while IFS= read -r -d '' launcher; do
    command_name="$(basename "${launcher}")"

    if [[ -e "${PACKAGE_BIN_DIR}/${command_name}" ]]; then
      echo "Duplicate launcher name: ${command_name}" >&2
      exit 1
    fi

    install \
      -m 0755 \
      "${launcher}" \
      "${PACKAGE_BIN_DIR}/${command_name}"

    echo "Included launcher ${command_name} from ${module}"
  done < <(
    find "${launcher_dir}" \
      -maxdepth 1 \
      -type f \
      -print0 |
    sort -z
  )
}

install_man_tree() {
  local source_root="$1"
  local man_page
  local relative_path
  local section_directory
  local destination_directory
  local destination_file

  if [[ ! -d "${source_root}" ]]; then
    return
  fi

  while IFS= read -r -d '' man_page; do
    relative_path="${man_page#${source_root}/}"
    section_directory="$(dirname "${relative_path}")"
    destination_directory="${PACKAGE_MAN_DIR}/${section_directory}"
    destination_file="${destination_directory}/$(basename "${man_page}").gz"

    if [[ -e "${destination_file}" ]]; then
      echo "Duplicate manual page: ${relative_path}" >&2
      exit 1
    fi

    mkdir -p "${destination_directory}"
    gzip -9 -n -c "${man_page}" > "${destination_file}"
    chmod 0644 "${destination_file}"
    echo "Included manual page ${relative_path}"
  done < <(
    find "${source_root}" \
      -mindepth 2 \
      -maxdepth 2 \
      -type f \
      -path '*/man[1-9]/*' \
      -print0 |
    sort -z
  )
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

  chmod 0644 "${PACKAGE_ROOT}${SYSTEMD_DIR}/maps-logger.service"

  install \
    -m 0644 \
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger-compress.service" \
    "${PACKAGE_ROOT}${SYSTEMD_DIR}/maps-logger-compress.service"

  install \
    -m 0644 \
    "${LOGGER_SYSTEMD_SOURCE}/maps-logger-compress.timer" \
    "${PACKAGE_ROOT}${SYSTEMD_DIR}/maps-logger-compress.timer"

  cat > "${PACKAGE_CONTROL_DIR}/conffiles" <<EOF_CONFFILES
${LOGGER_CONFIG_DIR}/env
EOF_CONFFILES

  echo "Included MQTT logger systemd units and configuration"
}

for module in "${MODULES[@]}"; do
  jar_file="$(find_module_jar "${module}")"
  launcher_dir="${ROOT_DIR}/${module}/install/bin"

  install_module_jar "${module}" "${jar_file}"

  if [[ "${module}" != "${LOGGER_MODULE}" ]]; then
    if [[ -d "${launcher_dir}" ]]; then
      install_module_launchers "${module}"
    else
      install_default_launcher "${module}"
    fi
  fi

  install_man_tree "${ROOT_DIR}/${module}/install/man"
done

install_logger_resources
install_man_tree "${ROOT_DIR}/packaging/install/man"

cat > "${PACKAGE_CONTROL_DIR}/control" <<EOF_CONTROL
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
EOF_CONTROL

cat > "${PACKAGE_CONTROL_DIR}/postinst" <<'EOF_POSTINST'
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
EOF_POSTINST

cat > "${PACKAGE_CONTROL_DIR}/prerm" <<'EOF_PRERM'
#!/bin/sh
set -e

if [ "$1" = "remove" ] && [ -d /run/systemd/system ]; then
  systemctl stop maps-logger.service >/dev/null 2>&1 || true
  systemctl stop maps-logger-compress.timer >/dev/null 2>&1 || true

  systemctl disable maps-logger.service >/dev/null 2>&1 || true
  systemctl disable maps-logger-compress.timer >/dev/null 2>&1 || true
fi

exit 0
EOF_PRERM

cat > "${PACKAGE_CONTROL_DIR}/postrm" <<'EOF_POSTRM'
#!/bin/sh
set -e

if [ -d /run/systemd/system ]; then
  systemctl daemon-reload >/dev/null 2>&1 || true
fi

exit 0
EOF_POSTRM

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
find "${PACKAGE_INSTALL_DIR}" \
  -maxdepth 1 \
  -type f \
  -printf '  %f\n' |
sort

echo
echo "Installed commands:"
find "${PACKAGE_BIN_DIR}" \
  -maxdepth 1 \
  -type f \
  -printf '  %f\n' |
sort

echo
echo "Installed manual pages:"
find "${PACKAGE_MAN_DIR}" \
  -type f \
  -printf '  %P\n' |
sort

echo
echo "Installed systemd units:"
find "${PACKAGE_ROOT}${SYSTEMD_DIR}" \
  -maxdepth 1 \
  -type f \
  -printf '  %f\n' |
sort
