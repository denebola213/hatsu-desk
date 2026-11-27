#!/usr/bin/env bash
# Android ビルドをコンテナで回す。
#
#   scripts/abuild.sh                 # assembleDebug
#   scripts/abuild.sh assembleRelease # タスク指定
#   scripts/abuild.sh lint testDebugUnitTest
#
# 常駐させない。毎回 --rm で起動し、Gradle のキャッシュだけ名前付きボリュームに残す。
# イメージを更新したのに古いコンテナが動き続ける、という事故を構造的に消すため。
set -euo pipefail

IMAGE="${ABUILD_IMAGE:-localhost/android-build:agp9.5-sdk37}"
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TASKS=("$@")
[ ${#TASKS[@]} -eq 0 ] && TASKS=(assembleDebug)

podman run --rm \
  -v "$PROJ:/work:Z" \
  -v android-build-gradle:/gradle \
  -w /work \
  "$IMAGE" \
  bash -lc "gradle --no-daemon --console=plain ${TASKS[*]}"

# ビルドが通っても、通信権限が混入していれば成果物にしない（ADR 0010）。
# ここが失敗すると set -e でスクリプト全体が失敗する。
echo "== INTERNET 権限の検査 =="
bash "$PROJ/scripts/check-no-internet.sh" --merged

