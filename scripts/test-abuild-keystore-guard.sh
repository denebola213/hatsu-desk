#!/usr/bin/env bash
# abuild.sh の鍵置き場ガードを固定する（Issue #17 / PR #23 の指摘）。
#
#   scripts/test-abuild-keystore-guard.sh
#
# 見るもの:
#   1. ABUILD_KEYSTORE_DIR がリポジトリ直下と**等しい**ときに exit 1
#      （子孫だけを弾くと、リポジトリ全体が /root/.android として RW マウントされる）
#   2. リポジトリの子孫・リポジトリを指すシンボリックリンクも exit 1
#   3. リポジトリの外なら通り、ディレクトリは 700・debug.keystore は 600 になる
#      （既存鍵が 644 で残っていても締め直す）
#
# podman を一時ディレクトリのスタブに差し替えるので、コンテナもイメージも要らない。
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABUILD="$PROJ/scripts/abuild.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$tmp/bin"
cat > "$tmp/bin/podman" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$tmp/podman.log"
# keytool がコンテナ内で鍵を作るのを模す（マウント先＝ホスト側にも 644 で置く）
case " \$* " in
  *" keytool "*)
    for a in "\$@"; do
      case "\$a" in
        *:/root/.android:Z)
          d="\${a%:/root/.android:Z}"
          mkdir -p "\$d"
          : > "\$d/debug.keystore"
          chmod 644 "\$d/debug.keystore"
          ;;
      esac
    done
    ;;
esac
exit 0
EOF
chmod +x "$tmp/bin/podman"

fail=0
pass() { echo "ok   $*"; }
ng() { echo "NG   $*" >&2; fail=1; }

# ガードに当たる（＝podman を一度も呼ばずに exit 1 する）ことを確かめる。
expect_rejected() { # expect_rejected <説明> <keystore_dir>
  local desc="$1" dir="$2" out rc=0
  : > "$tmp/podman.log"
  out="$(PATH="$tmp/bin:$PATH" ABUILD_KEYSTORE_DIR="$dir" bash "$ABUILD" 2>&1)" || rc=$?
  if [ "$rc" -ne 1 ]; then
    ng "$desc: exit 1 でなく $rc"
  elif ! grep -q 'NG: 鍵の置き場がリポジトリの中' <<<"$out"; then
    ng "$desc: エラー文言が出ない: $out"
  elif [ -s "$tmp/podman.log" ]; then
    ng "$desc: 拒否より先に podman を呼んでいる"
  else
    pass "$desc"
  fi
}

expect_rejected "リポジトリ直下と等しい" "$PROJ"
expect_rejected "末尾スラッシュ付きで等しい" "$PROJ/"
expect_rejected "リポジトリの子孫" "$PROJ/keystore"
expect_rejected "リポジトリの子孫（未作成）" "$PROJ/.android/hatsu-desk"

ln -s "$PROJ" "$tmp/repo-link"
expect_rejected "リポジトリを指すシンボリックリンク" "$tmp/repo-link"

# 外なら通り、鍵とディレクトリの権限が締まる。
mkdir -p "$tmp/keys"
: > "$tmp/keys/debug.keystore"
chmod 644 "$tmp/keys/debug.keystore"
rc=0
PATH="$tmp/bin:$PATH" ABUILD_KEYSTORE_DIR="$tmp/keys" bash "$ABUILD" >/dev/null 2>&1 || rc=$?
if [ "$rc" -ne 0 ]; then
  ng "リポジトリの外: exit 0 でなく $rc"
else
  pass "リポジトリの外は通る"
fi

# 未作成の置き場も通る（初回は鍵ディレクトリが無い。`set -e` で抜けないこと）。
rc=0
PATH="$tmp/bin:$PATH" ABUILD_KEYSTORE_DIR="$tmp/new/keys" bash "$ABUILD" >/dev/null 2>&1 || rc=$?
if [ "$rc" -ne 0 ]; then
  ng "未作成の置き場: exit 0 でなく $rc"
elif [ "$(stat -c '%a' "$tmp/new/keys")" != "700" ]; then
  ng "未作成の置き場: 作られたディレクトリが 700 でない"
else
  pass "未作成の置き場は作られる"
fi

mode_dir="$(stat -c '%a' "$tmp/keys")"
mode_key="$(stat -c '%a' "$tmp/keys/debug.keystore")"
[ "$mode_dir" = "700" ] && pass "鍵ディレクトリが 700" || ng "鍵ディレクトリが $mode_dir（700 でない）"
[ "$mode_key" = "600" ] && pass "既存の debug.keystore が 600" || ng "debug.keystore が $mode_key（600 でない）"

[ $fail -eq 0 ] && echo "OK: 鍵置き場のガードと権限"
exit $fail
