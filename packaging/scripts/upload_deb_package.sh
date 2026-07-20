#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_DIR="${ROOT_DIR}/packaging/output"
NEXUS_URL="https://repository.mapsmessaging.io"

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <nexus-user> <nexus-password> <repository-name>" >&2
  exit 1
fi

NEXUS_USER="$1"
NEXUS_PASSWORD="$2"
REPOSITORY_NAME="$3"

command -v curl >/dev/null 2>&1 || {
  echo "curl is required" >&2
  exit 1
}

command -v jq >/dev/null 2>&1 || {
  echo "jq is required" >&2
  exit 1
}

command -v dpkg-deb >/dev/null 2>&1 || {
  echo "dpkg-deb is required" >&2
  exit 1
}

mapfile -t packages < <(
  find "${OUTPUT_DIR}" \
    -maxdepth 1 \
    -type f \
    -name 'maps-apps_*.deb' \
    -print
)

if [[ ${#packages[@]} -eq 0 ]]; then
  echo "No maps-apps Debian package found in ${OUTPUT_DIR}" >&2
  exit 1
fi

if [[ ${#packages[@]} -gt 1 ]]; then
  echo "Multiple maps-apps Debian packages found in ${OUTPUT_DIR}:" >&2
  printf '  %s\n' "${packages[@]}" >&2
  exit 1
fi

PACKAGE_FILE="${packages[0]}"
PACKAGE_NAME="$(dpkg-deb -f "${PACKAGE_FILE}" Package)"
PACKAGE_VERSION="$(dpkg-deb -f "${PACKAGE_FILE}" Version)"

nexus_get() {
  curl \
    --connect-timeout 15 \
    --max-time 60 \
    --retry 2 \
    --retry-delay 3 \
    --fail \
    --silent \
    --show-error \
    --user "${NEXUS_USER}:${NEXUS_PASSWORD}" \
    "$@"
}

delete_existing_components() {
  local continuation_token=""
  local response
  local component_ids=()

  echo "Searching Nexus for ${PACKAGE_NAME} ${PACKAGE_VERSION}"

  while true; do
    local request_args=(
      --get
      "${NEXUS_URL}/service/rest/v1/search"
      --data-urlencode "repository=${REPOSITORY_NAME}"
      --data-urlencode "name=${PACKAGE_NAME}"
      --data-urlencode "version=${PACKAGE_VERSION}"
    )

    if [[ -n "${continuation_token}" ]]; then
      request_args+=(
        --data-urlencode "continuationToken=${continuation_token}"
      )
    fi

    response="$(nexus_get "${request_args[@]}")"

    mapfile -t component_ids < <(
      jq -r \
        --arg repository "${REPOSITORY_NAME}" \
        --arg name "${PACKAGE_NAME}" \
        --arg version "${PACKAGE_VERSION}" \
        '
          .items[]
          | select(
              .repository == $repository
              and .name == $name
              and .version == $version
            )
          | .id
        ' <<< "${response}"
    )

    for component_id in "${component_ids[@]}"; do
      echo "Deleting existing component ${component_id}"

      curl \
        --connect-timeout 15 \
        --max-time 60 \
        --retry 2 \
        --retry-delay 3 \
        --fail \
        --silent \
        --show-error \
        --user "${NEXUS_USER}:${NEXUS_PASSWORD}" \
        --request DELETE \
        "${NEXUS_URL}/service/rest/v1/components/${component_id}"
    done

    continuation_token="$(
      jq -r '.continuationToken // empty' <<< "${response}"
    )"

    if [[ -z "${continuation_token}" ]]; then
      break
    fi
  done
}

upload_package() {
  echo "Uploading $(basename "${PACKAGE_FILE}") to ${REPOSITORY_NAME}"

  curl \
    --connect-timeout 15 \
    --max-time 300 \
    --retry 2 \
    --retry-delay 5 \
    --fail \
    --show-error \
    --silent \
    --user "${NEXUS_USER}:${NEXUS_PASSWORD}" \
    --request POST \
    --form "deb.asset=@${PACKAGE_FILE}" \
    "${NEXUS_URL}/service/rest/v1/components?repository=${REPOSITORY_NAME}"

  echo "Uploaded ${PACKAGE_NAME} ${PACKAGE_VERSION}"
}

echo "Package:    ${PACKAGE_NAME}"
echo "Version:    ${PACKAGE_VERSION}"
echo "Repository: ${REPOSITORY_NAME}"
echo "File:       ${PACKAGE_FILE}"

delete_existing_components
upload_package

echo "Nexus publication completed"