#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script with sudo or as root." >&2
  exit 1
fi

if [[ ! -r /etc/os-release ]]; then
  echo "Unable to identify the operating system." >&2
  exit 1
fi

. /etc/os-release

if [[ "${ID}" != "ubuntu" || "${VERSION_ID}" != "24.04" ]]; then
  echo "This bootstrap script supports Ubuntu 24.04 only; found ${ID:-unknown} ${VERSION_ID:-unknown}." >&2
  exit 1
fi

TARGET_USER="${SUDO_USER:-}"
if [[ -z "${TARGET_USER}" || "${TARGET_USER}" == "root" ]]; then
  TARGET_USER="${MAPS_TEST_LAB_USER:-}"
fi

if [[ -z "${TARGET_USER}" ]]; then
  echo "Set MAPS_TEST_LAB_USER when running directly as root, for example:" >&2
  echo "  MAPS_TEST_LAB_USER=matthew ./bootstrap-ubuntu-24.04.sh" >&2
  exit 1
fi

if ! id "${TARGET_USER}" >/dev/null 2>&1; then
  echo "Target user does not exist: ${TARGET_USER}" >&2
  exit 1
fi

echo "Installing MAPS Test Lab host prerequisites for ${TARGET_USER}"

export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get install -y \
  ca-certificates \
  curl \
  git \
  gnupg \
  iproute2 \
  jq \
  maven \
  mosquitto-clients \
  openjdk-21-jdk-headless \
  procps \
  rsync \
  tcpdump \
  unzip \
  zip

for package in docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc; do
  if dpkg-query -W -f='${db:Status-Abbrev}' "${package}" 2>/dev/null | grep -q '^ii'; then
    apt-get remove -y "${package}"
  fi
done

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

ARCH="$(dpkg --print-architecture)"
CODENAME="${UBUNTU_CODENAME:-${VERSION_CODENAME}}"

cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${CODENAME}
Components: stable
Architectures: ${ARCH}
Signed-By: /etc/apt/keyrings/docker.asc
EOF

apt-get update
apt-get install -y \
  containerd.io \
  docker-buildx-plugin \
  docker-ce \
  docker-ce-cli \
  docker-compose-plugin

systemctl enable --now docker

if ! getent group docker >/dev/null; then
  groupadd docker
fi

usermod -aG docker "${TARGET_USER}"

install -d -m 0755 -o "${TARGET_USER}" -g "${TARGET_USER}" /srv/maps-test-lab
install -d -m 0755 -o "${TARGET_USER}" -g "${TARGET_USER}" /srv/maps-test-lab/instances
install -d -m 0755 -o "${TARGET_USER}" -g "${TARGET_USER}" /srv/maps-test-lab/evidence

echo
echo "Installed versions:"
java -version 2>&1 | head -n 1
mvn -version | head -n 1
git --version
docker --version
docker compose version
mosquitto_pub --help 2>&1 | head -n 1 || true

echo
echo "Verifying Docker daemon..."
docker info >/dev/null

echo
echo "Bootstrap complete."
echo "Log out and back in before using Docker as ${TARGET_USER}, so the docker group membership is applied."
echo
echo "Workspace:"
echo "  /srv/maps-test-lab"
echo
echo "Next:"
echo "  git clone https://github.com/Maps-Messaging/maps_apps.git"
echo "  cd maps_apps"
echo "  git checkout feat/MSG-333-mcp-test-lab"
echo "  mvn clean verify"
echo
echo "Docker group membership grants effectively root-level control of this isolated test server."
