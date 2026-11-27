#!/usr/bin/env bash
# git の credential helper。GitHub App の installation token を返す。
set -euo pipefail
[ "${1:-}" = "get" ] || exit 0
cat >/dev/null
. "${HERMES_HOME:-/home/hermeswebui/.hermes}/secrets/gh-app.env"
export GH_APP_ID GH_APP_INSTALLATION_ID
echo "username=x-access-token"
echo "password=$("$(dirname "$0")/gh-app-token.py" --token)"
