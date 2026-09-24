#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${MAPS_SERVER_REPO_URL:-https://github.com/Maps-Messaging/mapsmessaging_server.git}"
BRANCH="${MAPS_SERVER_BRANCH:-development}"
WORK_ROOT="${MAPS_SERVER_WORK_ROOT:-/srv/maps-test-lab/source}"
SOURCE_DIR="${WORK_ROOT}/mapsmessaging_server"
IMAGE_NAME="${MAPS_DOCKER_IMAGE:-mapsmessaging/server_daemon_local}"
RUN_TESTS="${MAPS_BUILD_TESTS:-false}"

mkdir -p "${WORK_ROOT}"

if [[ -d "${SOURCE_DIR}/.git" ]]; then
  echo "Updating MapsMessaging server source"
  git -C "${SOURCE_DIR}" fetch origin
  git -C "${SOURCE_DIR}" checkout "${BRANCH}"
  git -C "${SOURCE_DIR}" pull --ff-only origin "${BRANCH}"
else
  echo "Cloning MapsMessaging server branch ${BRANCH}"
  git clone --branch "${BRANCH}" --single-branch "${REPO_URL}" "${SOURCE_DIR}"
fi

cd "${SOURCE_DIR}"

VERSION="$(mvn --quiet --non-recursive -Dstyle.color=never help:evaluate -Dexpression=project.version -DforceStdout)"
if [[ -z "${VERSION}" ]]; then
  echo "Unable to determine MapsMessaging version" >&2
  exit 1
fi

if [[ "${RUN_TESTS}" == "true" ]]; then
  echo "Building MapsMessaging ${VERSION} with tests"
  mvn clean install
else
  echo "Building MapsMessaging ${VERSION} without tests"
  mvn clean install -DskipTests
fi

ARCHIVE="target/maps-${VERSION}-install.tar.gz"
if [[ ! -f "${ARCHIVE}" ]]; then
  echo "Expected install archive not found: ${SOURCE_DIR}/${ARCHIVE}" >&2
  exit 1
fi

ARCH="$(uname -m)"
case "${ARCH}" in
  x86_64|amd64)
    ZULU_ARCH="zulu21.44.17-ca-jdk21.0.8-linux_musl_x64.tar.gz"
    PLATFORM="linux/amd64"
    ;;
  aarch64|arm64)
    ZULU_ARCH="zulu21.44.17-ca-jdk21.0.8-linux_musl_aarch64.tar.gz"
    PLATFORM="linux/arm64"
    ;;
  *)
    echo "Unsupported host architecture: ${ARCH}" >&2
    exit 1
    ;;
esac

BUILD_DIR="${WORK_ROOT}/docker-build-${VERSION}"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"
cp "${ARCHIVE}" "${BUILD_DIR}/maps-install.tar.gz"

cat > "${BUILD_DIR}/Dockerfile" <<EOF
FROM alpine:3.22.1

ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US:en \
    LC_ALL=en_US.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/zulu-21 \
    PATH=/usr/lib/jvm/zulu-21/bin:\$PATH

RUN set -eux; \
    apk add --no-cache ca-certificates wget tar gzip coreutils dos2unix shadow; \
    update-ca-certificates; \
    wget -q "https://cdn.azul.com/zulu/bin/${ZULU_ARCH}"; \
    mkdir -p /usr/lib/jvm; \
    tar -xf "${ZULU_ARCH}" -C /usr/lib/jvm; \
    rm -f "${ZULU_ARCH}"; \
    mv "/usr/lib/jvm/${ZULU_ARCH%.tar.gz}" "\$JAVA_HOME"; \
    chmod +x "\$JAVA_HOME"/bin/*; \
    find "\$JAVA_HOME/bin" -type f -perm -a=x -exec ln -s {} /usr/bin/ \;

COPY maps-install.tar.gz /tmp/maps-install.tar.gz

RUN set -eux; \
    cd /; \
    tar -xf /tmp/maps-install.tar.gz; \
    rm /tmp/maps-install.tar.gz; \
    mv "/maps-${VERSION}" /opt/maps; \
    chmod +x /opt/maps/bin/startDocker.sh; \
    dos2unix /opt/maps/bin/startDocker.sh; \
    if [ -f /opt/maps/conf/docker_logback.xml ]; then \
      mv /opt/maps/conf/logback.xml /opt/maps/conf/logback.xml_orig; \
      mv /opt/maps/conf/docker_logback.xml /opt/maps/conf/logback.xml; \
    fi; \
    if [ -f /opt/maps/conf/NetworkManagerDocker.yaml ]; then \
      mv /opt/maps/conf/NetworkManager.yaml /opt/maps/conf/NetworkManager.yaml_orig; \
      mv /opt/maps/conf/NetworkManagerDocker.yaml /opt/maps/conf/NetworkManager.yaml; \
    fi; \
    mkdir -p /opt/maps_data; \
    addgroup -S messaginggroup; \
    adduser -S messaginguser -G messaginggroup -s /bin/sh -h /opt/maps; \
    chown -R messaginguser:messaginggroup /opt/maps_data /opt/maps; \
    chmod -R 755 /opt/maps

EXPOSE 9000/tcp 8080/tcp 8778/tcp 4222/tcp 1883/tcp 1884/udp 5683/udp 2442/udp
VOLUME /opt/maps_data
CMD su -c "ulimit -n 100000 && /opt/maps/bin/startDocker.sh" messaginguser
EOF

echo "Building native Docker image ${IMAGE_NAME} for ${PLATFORM}"
docker build --platform "${PLATFORM}" -t "${IMAGE_NAME}:latest" "${BUILD_DIR}"

echo
echo "Built:"
docker image inspect "${IMAGE_NAME}:latest" --format '  {{.RepoTags}} {{.Architecture}}/{{.Os}}'
echo
echo "Use this in lab.json:"
echo "  \"image\": \"${IMAGE_NAME}:latest\""
