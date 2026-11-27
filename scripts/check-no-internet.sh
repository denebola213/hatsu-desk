#!/usr/bin/env bash
# INTERNET 権限が混入していないことを確認する。
# このアプリは端末外と通信しない設計なので、依存が通信ライブラリを引き込んだら落とす。
#
#   scripts/check-no-internet.sh            # ソースの manifest
#   scripts/check-no-internet.sh --merged   # ビルド後の merged manifest も見る
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rc=0

check() {
  local f="$1"
  [ -f "$f" ] || return 0
  if grep -q 'android.permission.INTERNET' "$f"; then
    echo "NG: INTERNET 権限がある: $f" >&2
    rc=1
  fi
}

check "$PROJ/app/src/main/AndroidManifest.xml"

if [ "${1:-}" = "--merged" ]; then
  while IFS= read -r m; do check "$m"; done < <(
    find "$PROJ/app/build/intermediates" -name AndroidManifest.xml -path '*merged*' 2>/dev/null
  )
fi

[ $rc -eq 0 ] && echo "OK: INTERNET 権限なし"
exit $rc
