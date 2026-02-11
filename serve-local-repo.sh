#!/usr/bin/env bash
set -euo pipefail
REPO_DIR="$(pwd)/local-repo"
PORT="${1:-8000}"
if [ ! -d "${REPO_DIR}" ]; then
  echo "Creating ${REPO_DIR}"
  mkdir -p "${REPO_DIR}"
fi
cd "${REPO_DIR}"
echo "Serving ${REPO_DIR} on http://localhost:${PORT} (Ctrl-C to stop)"
python3 -m http.server "${PORT}"

