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
| マウント | プロジェクト → `/work`、名前付きボリューム `android-build-gradle` → `/gradle`、署名鍵 `~/.android/hatsu-desk` → `/root/.android` |

固定している値:

- cmdline-tools: `commandlinetools-linux-16111833_latest.zip` / SHA-1 `e025545c62a8e64c7559119566a569fb1dec5f60`
- Gradle: `gradle-9.6.0-bin.zip` / SHA-256 `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`

## ファイル

| 置き場 | 中身 |
| --- | --- |
| `container/Containerfile`（リポジトリ内） | イメージ定義。版はここが唯一の出所 |
| `scripts/abuild.sh` | ビルド実行（`--rm`）。署名鍵の作成とマウントもここ |
| `scripts/check-no-internet.sh` | `INTERNET` 権限の混入検査 |
| `scripts/check-keystore-not-tracked.sh` | 署名鍵が git に入っていないことの検査（直接実行できる） |
| `scripts/test-abuild-keystore-guard.sh` | 鍵置き場のガードと鍵の権限のテスト（podman 不要） |
| desktop `~/containers/android-build/Containerfile` | ビルド時にリポジトリから複製した実体 |
| desktop `~/.android/hatsu-desk/debug.keystore` | debug 署名鍵。**リポジトリの外**（下記） |

v1 では `/workspace/scratch/android-build/` に仮置きしていたが、リポジトリへ移した。環境の再現単位を git のコミットに合わせるため。

## 使い方

```bash
# desktop 上で（Hermes からは ssh 越し）。Containerfile は container/ にある
podman build --squash -t localhost/android-build:agp9.5-sdk37 container/
scripts/abuild.sh assembleDebug
scripts/abuild.sh lint testDebugUnitTest
```

`--squash` は付けないと cmdline-tools の zip（172MB）が層に残る。

## 署名鍵（debug.keystore）を持ち越す

`--rm` の都度起動なので、鍵をコンテナの中に作ると毎回別の鍵になり、APK の署名が変わる。すると `adb install -r` が `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で落ち、uninstall → install で**端末のデータと SYSTEM_ALERT_WINDOW などの許可が消える**（Issue #17）。

鍵はホストの `~/.android/hatsu-desk/` に置き、コンテナの `ANDROID_USER_HOME`（`/root/.android`）に丸ごとマウントする。コンテナが消えても鍵はホストに残る。

| 項目 | 値 |
| --- | --- |
| 置き場 | desktop `~/.android/hatsu-desk/debug.keystore`（`ABUILD_KEYSTORE_DIR` で変更可） |
| コンテナ内の見え方 | `/root/.android/debug.keystore` |
| 別名 | `androiddebugkey` |
| パスワード（store / key） | `android` / `android` |
| 鍵 | RSA 2048、有効 10000 日、`CN=Android Debug,O=Android,C=US` |
| 権限 | ディレクトリ `700`、`debug.keystore` `600` |
| git | 追跡しない（`.gitignore` の `*.keystore` / `*.jks` / `*.p12` / `*.pfx` / `/.android/`） |

鍵は debug 用の共有鍵なので秘密ではないが、リポジトリに入れると「どのビルドも同じ鍵」であることが repo の外から見えなくなる／別の用途に流用されうる。**入れない**運用にし、入っていないことを `scripts/check-keystore-not-tracked.sh` で検査する。

### 置き場のガード

`scripts/abuild.sh` は `ABUILD_KEYSTORE_DIR` が**リポジトリの中を指したら実行前に止まる**（`exit 1`）。

- 子孫（`$PWD/keystore` など）だけでなく、**リポジトリ直下と等しい場合も拒否する**。等値を許すと `-v "$KEYSTORE_DIR:/root/.android"` でリポジトリ全体がコンテナの `/root/.android` として RW マウントされる
- リポジトリを指すシンボリックリンクも、既存なら `realpath -e` で解決してから判定する
- 鍵を作ったあとは**新規生成・既存のどちらでも `debug.keystore` を `600`** にする（`keytool` は umask のまま 644 で作るため）

この判定と権限は `scripts/test-abuild-keystore-guard.sh` が固定する（`podman` をスタブに差し替えるので、イメージもコンテナも要らない）。

### 検査

```bash
scripts/check-keystore-not-tracked.sh     # 追跡・無視されていない鍵が無いこと
scripts/test-abuild-keystore-guard.sh     # 置き場のガードと権限
```

検査は追跡済みの鍵（`*.keystore` / `*.jks` / `*.p12` / `*.pfx`）と、作業ツリーにあって `.gitignore` で無視されていない鍵の両方を見る。追跡されていなければ `.gitignore` に載っていることまで確かめる（載っていない＝次の `git add .` で入りうる）。

### 作る（初回だけ）

`scripts/abuild.sh` が鍵の有無を見て、無ければ同じイメージの中で `keytool` で作る。手でやりたい場合・鍵を作り直したい場合は次を desktop で実行する（既存の鍵は上書きされるので、消す前に `cp` で退避する）。

```bash
mkdir -p ~/.android/hatsu-desk && chmod 700 ~/.android/hatsu-desk
podman run --rm -v "$HOME/.android/hatsu-desk:/root/.android:Z" \
  localhost/android-build:agp9.5-sdk37 \
  keytool -genkeypair -keystore /root/.android/debug.keystore \
    -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
```

鍵の指紋:

```bash
podman run --rm -v "$HOME/.android/hatsu-desk:/root/.android:Z" \
  localhost/android-build:agp9.5-sdk37 \
  keytool -list -v -keystore /root/.android/debug.keystore -storepass android \
  | grep -E 'SHA256:|Alias name:'
```

### 別マシンで同じ鍵にする（再現手順）

新しいビルド環境でも `install -r` で上書きできるようにするには、**同じ鍵**が要る。順番は次のどちらか。

1. **既存の鍵を配る（推奨・端末のデータが残る）**
   ```bash
   # 既存の desktop から
   scp desktop:~/.android/hatsu-desk/debug.keystore /tmp/debug.keystore
   # 新しいマシンで
   mkdir -p ~/.android/hatsu-desk && chmod 700 ~/.android/hatsu-desk
   install -m 600 /tmp/debug.keystore ~/.android/hatsu-desk/debug.keystore
   rm -f /tmp/debug.keystore
   ```
   置いたら `keytool -list -v` の `SHA256:` が元と一致することを確かめる。
2. **新しく作る（端末のデータは消える）**
   `scripts/abuild.sh` を 1 回走らせれば作られる。ただし鍵が違うので、その端末に入っている旧アプリは一度 uninstall が要る（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。以後は安定する。

鍵はどのマシンでも `~/.android/hatsu-desk/debug.keystore` に置く。このパスを変えた場合は `ABUILD_KEYSTORE_DIR` を両方のマシンで同じに設定する。

### 署名が変わっていないことの確認

```bash
scripts/abuild.sh assembleDebug          # 1 回目
cp app/build/outputs/apk/debug/app-debug.apk /tmp/apk1.apk
scripts/abuild.sh clean assembleDebug    # 2 回目（鍵が残っているかを見る）
# apksigner は build-tools の中にあり、イメージの PATH には無い
podman run --rm -v "$PWD:/work:Z" -w /work localhost/android-build:agp9.5-sdk37 bash -lc '
  apksigner=$(ls -1 /opt/android-sdk/build-tools/*/apksigner | sort -V | tail -1)
  "$apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
'
```

`certificate SHA-256 digest` が 1 回目と 2 回目で一致すればよい（build-tools 37 の apksigner は `V2 Signer: certificate SHA-256 digest: ...` と出す）。`scripts/abuild.sh` もビルドの最後にこの行を出す。

実機での `adb install -r` は端末を占有するので、このリポジトリのビルド検証では自動化しない（`install -r` が通ればデータは残る、という ADB の仕様に依拠する）。

## 検証結果

2026-11-28 / 署名鍵の共有（Issue #17）を入れた状態で `clean assembleDebug` を 2 回。署名証明書 `SHA-256` は 2 回とも `a1f13856cc97ec86ed9c51b3f0a3bffbe2708a335a93f6153b4d27c8dd6c3833`、APK の `sha256sum` も一致した。鍵の mtime とハッシュは 2 回目で変わらず（＝作り直されていない）。修正前の `abuild.sh` で同じことをすると 2 回で別の指紋（`1689b10a…` / `8d169554…`）になった。

2026-11-28 / PR #23 の指摘対応（リポジトリ直下の拒否・鍵の `600`・`*.pfx` の検査）を入れて `clean assembleDebug` を 2 回。署名証明書 `SHA-256` は 2 回とも `a1f13856cc97ec86ed9c51b3f0a3bffbe2708a335a93f6153b4d27c8dd6c3833`、APK の `sha256sum` も 2 回とも `ab0f0428…` で一致した。鍵は 1 回目で `644 → 600` になり、2 回目も `600` のまま（`== debug keystore を作る ==` はどちらにも出ない＝作り直されていない）。`ABUILD_KEYSTORE_DIR=$PWD` は `exit 1`、`scripts/check-keystore-not-tracked.sh` は直接実行で `OK`、`scripts/test-abuild-keystore-guard.sh` は 9 件すべて ok。

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
