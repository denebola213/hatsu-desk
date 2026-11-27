#!/bin/sh
# git の credential helper。GitHub App の installation token を返す。
# POSIX sh で書く（bash の場所に依存させない）。
set -eu
[ "${1:-}" = "get" ] || exit 0
cat >/dev/null
dir=$(cd "$(dirname "$0")" && pwd)
. "${HERMES_HOME:-/home/hermeswebui/.hermes}/secrets/gh-app.env"
export GH_APP_ID GH_APP_INSTALLATION_ID
echo "username=x-access-token"
printf 'password=%s\n' "$(/usr/local/bin/python3 "$dir/gh-app-token.py" --token)"
