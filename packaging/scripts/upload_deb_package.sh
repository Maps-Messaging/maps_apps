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

mapfile -t packages < <(
find "${OUTPUT_DIR}" -maxdepth 1 -type f -name 'maps-apps_*.deb' -print
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

delete_existing_component() {
local continuation_token=""
local query_url
local response
local component_ids

while true; do
query_url="${NEXUS_URL}/service/rest/v1/components?repository=${REPOSITORY_NAME}"

```
if [[ -n "${continuation_token}" ]]; then
  query_url="${query_url}&continuationToken=${continuation_token}"
fi

response="$(
  curl \
    --fail \
    --silent \
    --show-error \
    --user "${NEXUS_USER}:${NEXUS_PASSWORD}" \
    "${query_url}"
)"

mapfile -t component_ids < <(
  jq -r \
    --arg name "${PACKAGE_NAME}" \
    --arg version "${PACKAGE_VERSION}" \
    '.items[]
     | select(.name == $name and .version == $version)
     | .id' <<< "${response}"
)

for component_id in "${component_ids[@]}"; do
  echo "Deleting existing ${PACKAGE_NAME} ${PACKAGE_VERSION}: ${component_id}"

  curl \
    --fail \
    --silent \
    --show-error \
    --user "${NEXUS_USER}:${NEXUS_PASSWORD}" \
    --request DELETE \
    "${NEXUS_URL}/service/rest/v1/components/${component_id}"
done

continuation_token="$(jq -r '.continuationToken // empty' <<< "${response}")"

if [[ -z "${continuation_token}" ]]; then
  break
fi
```

done
}

upload_package() {
echo "Uploading $(basename "${PACKAGE_FILE}") to ${REPOSITORY_NAME}"

curl
--fail
--silent
--show-error
--user "${NEXUS_USER}:${NEXUS_PASSWORD}"
--request POST
"${NEXUS_URL}/service/rest/v1/components?repository=${REPOSITORY_NAME}"
--form "deb.asset=@${PACKAGE_FILE}"

echo "Uploaded ${PACKAGE_NAME} ${PACKAGE_VERSION}"
}

command -v jq >/dev/null 2>&1 || {
echo "jq is required" >&2
exit 1
}

delete_existing_component
upload_package
