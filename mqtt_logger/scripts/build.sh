#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn clean package -pl mqtt_logger
