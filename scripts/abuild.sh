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
