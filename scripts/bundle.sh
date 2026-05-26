#!/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "Building..."
"$REPO_ROOT/scripts/build.sh"

# Find the shaded JAR (mqtt_logger-<version>.jar in target/)
JAR=$(ls "$REPO_ROOT/mqtt_logger/target/mqtt_logger-"*.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
  echo "Error: shaded JAR not found in mqtt_logger/target/" >&2
  exit 1
fi

BUNDLE_DIR="$REPO_ROOT/maps-logger-bundle"
TARBALL="$REPO_ROOT/maps-logger-install.tar.gz"

echo "Assembling bundle..."
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"

cp "$JAR" "$BUNDLE_DIR/"
# Clean up any old staged JARs before placing the new one
rm -f "$REPO_ROOT/install/docker/mqtt_logger-"*.jar
cp "$JAR" "$REPO_ROOT/install/docker/$(basename "$JAR")"
cp -r "$REPO_ROOT/install/." "$BUNDLE_DIR/"

tar czf "$TARBALL" -C "$REPO_ROOT" "$(basename "$BUNDLE_DIR")/"
rm -rf "$BUNDLE_DIR"

echo "Bundle ready: $TARBALL"
