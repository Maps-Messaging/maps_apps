#!/bin/bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "Building..."
"$PROJECT_ROOT/scripts/build.sh"

JAR=$(find "$PROJECT_ROOT/target" \
  -maxdepth 1 \
  -type f \
  -name 'mqtt_logger-*.jar' \
  ! -name '*-sources.jar' \
  ! -name '*-javadoc.jar' \
  ! -name '*-tests.jar' \
  -printf '%T@ %p\n' \
  2>/dev/null \
  | sort -nr \
  | head -1 \
  | cut -d' ' -f2- || true)

if [[ -z "$JAR" ]]; then
  echo "Error: shaded JAR not found in target/" >&2
  exit 1
fi

BUNDLE_DIR="$PROJECT_ROOT/maps-logger-bundle"
TARBALL="$PROJECT_ROOT/maps-logger-install.tar.gz"

echo "Assembling bundle..."
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"
mkdir -p "$PROJECT_ROOT/install/docker"

cp "$JAR" "$BUNDLE_DIR/"

rm -f "$PROJECT_ROOT/install/docker/mqtt_logger-"*.jar
cp "$JAR" "$PROJECT_ROOT/install/docker/$(basename "$JAR")"

cp -r "$PROJECT_ROOT/install/." "$BUNDLE_DIR/"

tar czf "$TARBALL" -C "$PROJECT_ROOT" "$(basename "$BUNDLE_DIR")/"
rm -rf "$BUNDLE_DIR"

echo "Bundle ready: $TARBALL"