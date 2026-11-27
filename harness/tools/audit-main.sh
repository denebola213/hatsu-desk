#!/usr/bin/env bash
# main の各コミットが PR 由来かを GitHub に問い合わせる。
#
# ブランチ保護は「入り口」を塞ぐが、入った後に壊れていないかは別に見る必要がある。
# これは事後監査で、事前ブロックではない。
#
# 使い方: harness/tools/audit-main.sh [件数]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$HERE/../.." && pwd)"
REPO="${GITHUB_REPO:-denebola213/hatsu-desk}"
N="${1:-20}"
GH="$HERE/gh"

cd "$REPO_DIR"
git fetch -q origin main 2>/dev/null || true

bad=0
while read -r sha; do
  count=$("$GH" api "repos/$REPO/commits/$sha/pulls" --jq 'length' 2>/dev/null || echo "-1")
  subject=$(git log -1 --format='%s' "$sha")
  if [ "$count" = "0" ]; then
    echo "NO_PR  $sha  $subject"
    bad=$((bad + 1))
  elif [ "$count" = "-1" ]; then
    echo "ERROR  $sha  問い合わせに失敗"
    bad=$((bad + 1))
  fi
done < <(git rev-list -n "$N" origin/main)

if [ "$bad" -eq 0 ]; then
  echo "OK: 直近 $N 件はすべて PR 由来"
else
  echo "要確認: $bad 件"
  exit 1
fi
