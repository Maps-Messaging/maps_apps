#!/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ $# -eq 0 ]]; then
  echo "Usage: deploy.sh <user@host> [<user@host>...]" >&2
  exit 1
fi

echo "Building and bundling..."
"$REPO_ROOT/scripts/bundle.sh"
TARBALL="$REPO_ROOT/maps-logger-install.tar.gz"

FAILED=0

for HOST in "$@"; do
  echo ""
  echo "==> Deploying to $HOST"

  if ! scp "$TARBALL" "$HOST:/tmp/maps-logger-install.tar.gz"; then
    echo "ERROR: failed to copy tarball to $HOST" >&2
    FAILED=$((FAILED + 1))
    continue
  fi

  if ! ssh "$HOST" \
      "cd /tmp && tar xzf maps-logger-install.tar.gz && sudo ./maps-logger-bundle/install.sh"; then
    echo "ERROR: install.sh failed on $HOST" >&2
    FAILED=$((FAILED + 1))
    continue
  fi

  echo "==> $HOST: OK"
done

echo ""
if [[ $FAILED -gt 0 ]]; then
  echo "$FAILED host(s) failed." >&2
  exit 1
fi

echo "All hosts deployed successfully."
