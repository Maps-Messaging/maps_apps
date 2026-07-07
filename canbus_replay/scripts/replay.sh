#!/usr/bin/env bash

set -euo pipefail

APP_CLASS="io.mapsmessaging.canbus.app.CanbusNdjsonReplayApp"
JAR_NAME="canbus-replay.jar"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="${SCRIPT_DIR}/${JAR_NAME}"

if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Unable to find ${JAR_NAME} beside this script:"
  echo "  ${JAR_PATH}"
  exit 1
fi

read -rp "Replay directory: " REPLAY_DIR
read -rp "CAN interface [vcan0]: " CAN_INTERFACE
read -rp "Loop replay? [y/N]: " LOOP_REPLY
read -rp "Replay speed [1]: " REPLAY_SPEED

CAN_INTERFACE="${CAN_INTERFACE:-vcan0}"
REPLAY_SPEED="${REPLAY_SPEED:-1}"

if [[ -z "${REPLAY_DIR}" ]]; then
  echo "Replay directory is required"
  exit 1
fi

if [[ ! -d "${REPLAY_DIR}" ]]; then
  echo "Replay directory does not exist:"
  echo "  ${REPLAY_DIR}"
  exit 1
fi

ARGS=(
  "--dir" "${REPLAY_DIR}"
  "--interface" "${CAN_INTERFACE}"
  "--speed" "${REPLAY_SPEED}"
)

case "${LOOP_REPLY}" in
  y|Y|yes|YES)
    ARGS+=("--loop")
    ;;
esac

echo
echo "Starting CAN replay..."
echo "  Jar:       ${JAR_PATH}"
echo "  Directory: ${REPLAY_DIR}"
echo "  Interface: ${CAN_INTERFACE}"
echo "  Speed:     ${REPLAY_SPEED}x"
echo "  Loop:      ${LOOP_REPLY:-N}"
echo

exec java -cp "${JAR_PATH}" "${APP_CLASS}" "${ARGS[@]}"