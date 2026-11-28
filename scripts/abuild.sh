#!/usr/bin/env bash
# Android ビルドをコンテナで回す。
#
#   scripts/abuild.sh                 # assembleDebug
#   scripts/abuild.sh assembleRelease # タスク指定
#   scripts/abuild.sh lint testDebugUnitTest
#
# 常駐させない。毎回 --rm で起動し、Gradle のキャッシュだけ名前付きボリュームに残す。
# イメージを更新したのに古いコンテナが動き続ける、という事故を構造的に消すため。
#
# 署名鍵（debug.keystore）はリポジトリの外に置き、コンテナの /root/.android に
# マウントする。コンテナと一緒に鍵が消えると APK の署名が毎回変わり、
# `adb install -r` が INSTALL_FAILED_UPDATE_INCOMPATIBLE で落ちて端末のデータと
# 許可が消える（Issue #17）。置き場は ABUILD_KEYSTORE_DIR で変えられる。
set -euo pipefail

IMAGE="${ABUILD_IMAGE:-localhost/android-build:agp9.5-sdk37}"
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_DIR="${ABUILD_KEYSTORE_DIR:-$HOME/.android/hatsu-desk}"

TASKS=("$@")
[ ${#TASKS[@]} -eq 0 ] && TASKS=(assembleDebug)

# 鍵はリポジトリの外に置く。コンテナの ANDROID_USER_HOME=/root/.android に
# このディレクトリを丸ごと当てるので、鍵は $KEYSTORE_DIR/debug.keystore に残る。
# realpath -m はディレクトリが未作成でも正規化する（作成前に判定したい）。
case "$(realpath -m "$KEYSTORE_DIR")" in
  "$(realpath -m "$PROJ")"/*) echo "NG: 鍵の置き場がリポジトリの中: $KEYSTORE_DIR" >&2; exit 1 ;;
esac
mkdir -p "$KEYSTORE_DIR"
chmod 700 "$KEYSTORE_DIR"

# 鍵が無ければ作る。別名・パスワード・鍵長・有効期限は AGP の既定
# （androiddebugkey / android / RSA 2048 / 10000 日）に合わせる。
# ホストに keytool を要求しないよう、同じイメージの中で作る。
if [ ! -f "$KEYSTORE_DIR/debug.keystore" ]; then
  echo "== debug keystore を作る: $KEYSTORE_DIR/debug.keystore =="
  podman run --rm \
    -v "$KEYSTORE_DIR:/root/.android:Z" \
    "$IMAGE" \
    keytool -genkeypair \
      -keystore /root/.android/debug.keystore \
      -storepass android -keypass android \
      -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US"
fi

podman run --rm \
  -v "$PROJ:/work:Z" \
  -v android-build-gradle:/gradle \
  -v "$KEYSTORE_DIR:/root/.android:Z" \
  -w /work \
  "$IMAGE" \
  bash -lc "gradle --no-daemon --console=plain ${TASKS[*]}"

# ビルドが通っても、通信権限が混入していれば成果物にしない（ADR 0010）。
# ここが失敗すると set -e でスクリプト全体が失敗する。
echo "== INTERNET 権限の検査 =="
bash "$PROJ/scripts/check-no-internet.sh" --merged

# 署名が今の鍵のものかを毎回出す。2 回続けてビルドして、この値が変わっていたら
# 鍵が別物になっている（＝また install -r が通らなくなる）。
# apksigner は build-tools の中にあり、イメージの PATH には入っていない。
APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$PROJ/$APK" ]; then
  echo "== 署名証明書 =="
  podman run --rm -v "$PROJ:/work:Z" -w /work "$IMAGE" bash -lc '
    apksigner=$(ls -1 /opt/android-sdk/build-tools/*/apksigner | sort -V | tail -1)
    "$apksigner" verify --print-certs "$1" \
      | grep -E "certificate (DN|SHA-256 digest)"
  ' _ "$APK"
fi
