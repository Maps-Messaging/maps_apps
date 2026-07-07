#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

APP_NAME="canbus_replay"
VERSION="1.0.0-SNAPSHOT"

JAR_NAME="${APP_NAME}-${VERSION}.jar"
ZIP_NAME="${APP_NAME}-${VERSION}.zip"

JAR_PATH="${PROJECT_DIR}/target/${JAR_NAME}"
REPLAY_SCRIPT_PATH="${PROJECT_DIR}/scripts/replay.sh"

DIST_DIR="${PROJECT_DIR}/target/dist"
PACKAGE_DIR="${DIST_DIR}/${APP_NAME}"
ZIP_PATH="${PROJECT_DIR}/target/${ZIP_NAME}"

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Missing jar:"
  echo "  ${JAR_PATH}"
  echo
  echo "Run:"
  echo "  mvn clean package"
  exit 1
fi

if [[ ! -f "${REPLAY_SCRIPT_PATH}" ]]; then
  echo "Missing replay script:"
  echo "  ${REPLAY_SCRIPT_PATH}"
  exit 1
fi

rm -rf "${DIST_DIR}"
mkdir -p "${PACKAGE_DIR}"

cp "${JAR_PATH}" "${PACKAGE_DIR}/"
cp "${REPLAY_SCRIPT_PATH}" "${PACKAGE_DIR}/replay.sh"

chmod +x "${PACKAGE_DIR}/replay.sh"

rm -f "${ZIP_PATH}"

(
  cd "${DIST_DIR}"
  zip -r "${ZIP_PATH}" "${APP_NAME}"
)

echo
echo "Built:"
echo "  ${ZIP_PATH}"
echo
echo "Contents:"
unzip -l "${ZIP_PATH}"