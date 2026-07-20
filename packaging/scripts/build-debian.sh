#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PACKAGE_NAME="maps-apps"
INSTALL_DIR="/opt/maps/apps"
OUTPUT_DIR="${ROOT_DIR}/packaging/output"
WORK_DIR="${ROOT_DIR}/packaging/work"

command -v dpkg-deb >/dev/null 2>&1 || {
  echo "dpkg-deb is required" >&2
  exit 1
}

command -v mvn >/dev/null 2>&1 || {
  echo "mvn is required" >&2
  exit 1
}

MAVEN_VERSION="$(mvn -q -N help:evaluate -Dexpression=project.version -DforceStdout)"
DEBIAN_VERSION="${MAVEN_VERSION/-SNAPSHOT/~snapshot}"
DEBIAN_VERSION="${DEBIAN_VERSION//-SNAPSHOT/~snapshot}"
ARCHITECTURE="all"
PACKAGE_ROOT="${WORK_DIR}/${PACKAGE_NAME}_${DEBIAN_VERSION}_${ARCHITECTURE}"

rm -rf "${PACKAGE_ROOT}"
mkdir -p \
  "${PACKAGE_ROOT}/DEBIAN" \
  "${PACKAGE_ROOT}${INSTALL_DIR}" \
  "${PACKAGE_ROOT}/usr/bin" \
  "${OUTPUT_DIR}"

mapfile -t MODULES < <(
  sed -n 's:.*<module>\([^<]*\)</module>.*:\1:p' "${ROOT_DIR}/pom.xml"
)

if [[ ${#MODULES[@]} -eq 0 ]]; then
  echo "No Maven modules found in ${ROOT_DIR}/pom.xml" >&2
  exit 1
fi

install_module() {
  local module="$1"
  local target_dir="${ROOT_DIR}/${module}/target"
  local jar_file
  local installed_name="${module}.jar"
  local command_name="maps-${module//_/-}"

  if [[ ! -d "${target_dir}" ]]; then
    echo "Missing target directory for module ${module}: ${target_dir}" >&2
    echo "Run Maven package before building the Debian package." >&2
    exit 1
  fi

  mapfile -t jars < <(
    find "${target_dir}" -maxdepth 1 -type f -name '*.jar' \
      ! -name 'original-*.jar' \
      ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' \
      ! -name '*-tests.jar' \
      -printf '%T@ %p\n' | sort -nr | cut -d' ' -f2-
  )

  if [[ ${#jars[@]} -eq 0 ]]; then
    echo "No deployable JAR found for module ${module} in ${target_dir}" >&2
    exit 1
  fi

  jar_file="${jars[0]}"
  install -m 0644 "${jar_file}" "${PACKAGE_ROOT}${INSTALL_DIR}/${installed_name}"

  cat > "${PACKAGE_ROOT}/usr/bin/${command_name}" <<LAUNCHER
#!/bin/sh
exec java -jar ${INSTALL_DIR}/${installed_name} "\$@"
LAUNCHER
  chmod 0755 "${PACKAGE_ROOT}/usr/bin/${command_name}"

  echo "Included ${module}: $(basename "${jar_file}")"
}

for module in "${MODULES[@]}"; do
  install_module "${module}"
done

cat > "${PACKAGE_ROOT}/DEBIAN/control" <<EOF_CONTROL
Package: ${PACKAGE_NAME}
Version: ${DEBIAN_VERSION}
Section: utils
Priority: optional
Architecture: ${ARCHITECTURE}
Depends: maps
Maintainer: MapsMessaging B.V. <matthew.buckton@mapsmessaging.io>
Homepage: https://www.mapsmessaging.io
Description: MapsMessaging command-line applications
 Collection of operational and diagnostic applications for MapsMessaging,
 installed under ${INSTALL_DIR}.
EOF_CONTROL

cat > "${PACKAGE_ROOT}/DEBIAN/postinst" <<'EOF_POSTINST'
#!/bin/sh
set -e

if [ "$1" = "configure" ]; then
  chmod 0755 /usr/bin/maps-* 2>/dev/null || true
fi

exit 0
EOF_POSTINST
chmod 0755 "${PACKAGE_ROOT}/DEBIAN/postinst"

OUTPUT_FILE="${OUTPUT_DIR}/${PACKAGE_NAME}_${DEBIAN_VERSION}_${ARCHITECTURE}.deb"
rm -f "${OUTPUT_FILE}"
dpkg-deb --root-owner-group --build "${PACKAGE_ROOT}" "${OUTPUT_FILE}"

echo "Built ${OUTPUT_FILE}"
