# ビルド環境 — Android ビルドコンテナ

2026-11-27 / 状態: 稼働確認済み（v2）

> v2 の変更: **常駐をやめ、`--rm` の都度起動にした**（ADR 0005）。v1 の「コンテナを常駐させて `podman exec` で駆動する」は誤っていた。イメージを AGP 9.5 / platform 37.2 に更新し、置き場をリポジトリへ移した。

Android のビルド環境を Hermes のコンテナに入れず、desktop 上の専用イメージで動かす。Hermes 側からは SSH 越しに `podman run --rm` で駆動する。

## なぜ入れ子にしないか（実測）

Hermes のコンテナ内でコンテナを動かす案は、条件が足りない。

| 条件 | 結果 |
| --- | --- |
| podman / docker / buildah | どれも無い。ソケットも未マウント |
| ユーザー名前空間 | 作れる（`unshare(CLONE_NEWUSER\|CLONE_NEWNS\|CLONE_NEWPID)` が rc=0） |
| `/proc` のマウント | **不可**（`unshare -Urpf --mount-proc` が `mount /proc failed: Permission denied`） |
| `/dev/fuse` | 無い。fuse-overlayfs が使えず vfs 固定（レイヤを丸ごとコピー） |
| 能力 | `CapEff=0`、`CapBnd=0x800405fb`（`CAP_SYS_ADMIN` を含まない）、seccomp フィルタ有効 |
| `sudo` | 通る。ただし bounding set に無い権限は得られない |

runc/crun は `/proc` のマウントと pivot_root を必要とするので、podman を入れてもコンテナは起動しない。user namespace の作成だけは成功するため、`unshare` の rc だけを見て「可能」と判断してはいけない。

Red Hat の podman-in-podman の記事が前提にする `--privileged`（または fuse デバイス＋unconfined な seccomp）を、この環境では満たせない。

## 構成（v2）

| 項目 | 値 |
| --- | --- |
| ホスト | desktop（192.168.20.100:22022、46GB RAM、16 コア） |
| ランタイム | podman 6.2.0（rootless、btrfs、1.1TB 空き） |
| イメージ | `localhost/android-build:agp9.5-sdk37` 1.46GB |
| ベース | `debian:bookworm-slim` + OpenJDK 17.0.21 |
| SDK | cmdline-tools 23.0.0、platform-tools 37.0.3、platforms android-36 / 37.0 / 37.2、build-tools 36.0.0 / 37.0.0 |
| Gradle | 9.6.0（イメージ内 `/opt/gradle`、wrapper も同版で生成） |
| 起動 | `--rm` の都度起動。常駐しない |
| マウント | プロジェクト → `/work`、名前付きボリューム `android-build-gradle` → `/gradle` |

固定している値:

- cmdline-tools: `commandlinetools-linux-16111833_latest.zip` / SHA-1 `e025545c62a8e64c7559119566a569fb1dec5f60`
- Gradle: `gradle-9.6.0-bin.zip` / SHA-256 `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`

## ファイル

| 置き場 | 中身 |
| --- | --- |
| `container/Containerfile`（リポジトリ内） | イメージ定義。版はここが唯一の出所 |
| `scripts/abuild.sh` | ビルド実行（`--rm`） |
| `scripts/check-no-internet.sh` | `INTERNET` 権限の混入検査 |
| desktop `~/containers/android-build/Containerfile` | ビルド時にリポジトリから複製した実体 |

v1 では `/workspace/scratch/android-build/` に仮置きしていたが、リポジトリへ移した。環境の再現単位を git のコミットに合わせるため。

## 使い方

```bash
# desktop 上で（Hermes からは ssh 越し）
podman build --squash -t localhost/android-build:agp9.5-sdk37 build/
scripts/abuild.sh assembleDebug
scripts/abuild.sh lint testDebugUnitTest
```

`--squash` は付けないと cmdline-tools の zip（172MB）が層に残る。

## 検証結果

`assembleDebug` が 21 秒で通り、APK が出た。

```
BUILD SUCCESSFUL in 21s
app/build/outputs/apk/debug/app-debug.apk  13M
```

`aapt2 dump permissions` の出力は `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` のみで、`INTERNET` は無い。`aapt2 dump badging` は `compileSdkVersion='37'`、`platformBuildVersionName='17'`、`application-label:'構成ビューア'`。

## 注意

- **`buildToolsVersion` を明示する。** AGP の既定はイメージに無い版を指すことがあり、その場合ビルド時に SDK へ取りに行く。その層はイメージに残らないので毎回取り直しになる。プロジェクト側で書き、イメージにも既定版を入れる
- **AGP 9 では `org.jetbrains.kotlin.android` を手で適用しない。** AGP が Kotlin を内蔵しており、手で当てると `ApplicationExtensionImpl cannot be cast to BaseExtension` で落ちる。Compose のコンパイラプラグインだけを当てる
- **`compileSdk = 37` は `android-37.0` を指す。** compose 1.13 は 37.1 以上を要求するので `compileSdkMinor = 2` を併記する
- Gradle はイメージにも入れてある（wrapper 生成とフォールバック用）。プロジェクトでは wrapper を使う
- `sdkmanager` は「Android CLI へ移行せよ」という警告を出す。動作に影響はない
- rootless podman なので、コンテナ内の root はホストの `denebola213` に写像される
- desktop が落ちているとビルドできない。単一障害点だが、SDK を複数箇所に置くより良い
