# hatsu-desk

学園アイドルマスターのランキング／プロデュース履歴から、上位プレイヤーの構成を端末内に貯めて一覧・集計する Android アプリ。

- **通信しない。** `INTERNET` 権限を宣言しない。サーバーを持たない。
- **操作は人間が行う。** 自動タップ、アクセシビリティサービスによる画面読取、メモリ読取、通信傍受は実装しない。
- **画像を再配布しない。** カード辞書はアプリに同梱せず、利用者の端末で育つ。

設計は SilverBullet の `Gakumas/` 配下（`design/01 設計 — ランキング構成ビューア`、`ADR/`）。

## ビルド

ビルド環境はコンテナ。ホスト（desktop）の rootless podman で動かし、都度起動する（常駐させない）。

Hermes 自身はコンテナの中で動いており、その内側に入れ子で podman を置くことはできない。実測で、`CapBnd` に `CAP_SYS_ADMIN` が無く（`0x800405fb`）、`/dev/fuse` も無いため、`unshare` で user namespace は作れても `mount /proc` が `Permission denied` になる。runc/crun はそこを必要とするので、podman を入れてもコンテナは起動しない。よって 1 段目のホストに置く。

```bash
scripts/abuild.sh                # assembleDebug
scripts/abuild.sh lint testDebugUnitTest
```

イメージを作り直すとき:

```bash
podman build --squash -t localhost/android-build:agp9.5-sdk37 container/
```

### 固定している版

| 要素 | 版 | 根拠 |
| --- | --- | --- |
| AGP | 9.5.0 | 現行 stable |
| Gradle | 9.6.0 | AGP 9.5 の要件 |
| JDK | 17 | AGP 9.x の要件 |
| Kotlin（AGP 内蔵） | 2.2.10 | AGP 9.5 が同梱 |
| build-tools | 37.0.0 | compileSdk 37 に合わせて固定（AGP 9.5 の既定は 36.0.0） |
| compileSdk / targetSdk | 37 / 36 | androidx.core 1.19 が compileSdk 37 を要求 |
| minSdk | 26 | |

**AGP 9 では `org.jetbrains.kotlin.android` を手で適用しない。** AGP が Kotlin コンパイルを内蔵しており、手で当てると二重適用で失敗する（`ApplicationExtensionImpl cannot be cast to BaseExtension`）。Compose のコンパイラプラグインだけを当てる。

## 実機へ

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 検査

```bash
scripts/check-no-internet.sh --merged   # 通信権限の混入を落とす
```

## 検証端末

| 項目 | 値 |
| --- | --- |
| 機種 | Google Pixel 10 Pro（blazer） |
| Android | API 37（17） |
| 画面 | 1080x2410 |
| 接続 | ワイヤレスデバッグ常時 ON、`adb connect 192.168.20.223:<接続ポート>` |

接続ポートは再起動で変わる。ペアリングは端末ごとに1回だけ（鍵は `~/.android/adbkey`）。`adb pair` のペア用ポートと `adb connect` の接続用ポートは別物。
