#!/usr/bin/env bash
# 署名鍵がリポジトリに入っていないことを確かめる（Issue #17）。
#
#   scripts/check-keystore-not-tracked.sh
#
# 見るもの:
#   1. git が追跡しているファイルに keystore / jks / p12 が無いこと
#   2. 作業ツリーにある鍵が .gitignore で無視されていること
#      （無視されていない＝次の `git add .` で入りうる、なので落とす）
#
# git 管理下のファイルだけを見るので、リポジトリの外（~/.android/hatsu-desk）に
# 置いた鍵は対象にならない。それが正しい置き方。
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJ"

rc=0

tracked="$(git ls-files | grep -E '(^|/)debug\.keystore$|\.(keystore|jks|p12|pfx)$' || true)"
if [ -n "$tracked" ]; then
  echo "NG: 署名鍵が git に入っている:" >&2
  printf '  %s\n' $tracked >&2
  rc=1
fi

# 追跡外でも、無視されていない鍵は「次に add されうる」ので落とす。
# 追跡済みのものは上で報告済み（check-ignore は追跡下のパスを ignored と言わない）。
while IFS= read -r f; do
  [ -n "$f" ] || continue
  git ls-files --error-unmatch "$f" >/dev/null 2>&1 && continue
  if ! git check-ignore -q "$f"; then
    echo "NG: 鍵が .gitignore で無視されていない: $f" >&2
    rc=1
  fi
done < <(find . -path ./.git -prune -o -type f \
           \( -name '*.keystore' -o -name '*.jks' -o -name '*.p12' \) -print 2>/dev/null)

[ $rc -eq 0 ] && echo "OK: 署名鍵はリポジトリに入っていない"
exit $rc
