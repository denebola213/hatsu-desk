# 0005 ビルド環境はホストの rootless podman に置き、常駐させない

- 状態: 採用
- 日付: 2026-11-27
- 置換: なし（ビルド環境の置き場所は初決定）

## 文脈

Android の開発環境をコンテナにまとめたい。Hermes 自身がコンテナの中で動いているため、その内側に入れ子で podman を置けるかを実測した。

- `CapEff=0`、`CapBnd=0x800405fb`。`CAP_SYS_ADMIN`（bit 21）を含まない。`sudo` は通るが bounding set に無い権限は得られない
- `/dev/fuse` が無い。fuse-overlayfs が使えず、vfs しか選べない
- `unshare -Urpf --mount-proc true` は `mount /proc failed: Permission denied`。user namespace の作成自体は成功する（`unshare(CLONE_NEWUSER|CLONE_NEWNS|CLONE_NEWPID)` が rc=0）ので、rc だけ見ると可能に見える

runc/crun は `/proc` のマウントと pivot_root を必要とする。したがって podman を入れてもコンテナは起動しない。Red Hat の podman-in-podman の記事が前提にしている `--privileged`（または fuse デバイスと unconfined な seccomp）を、この環境では満たせない。

また、前回は `podman run -d` で常駐させていた。理由は Gradle デーモンとキャッシュの温存だったが、実際には build-tools 35.0.1 のイメージのまま動き続け、新しいイメージを作っても使われなかった。

## 決定

1. ビルドコンテナは **SSH で届くホスト（desktop, 192.168.20.100）の rootless podman** で動かす。Hermes のコンテナには SDK を置かない。
2. **常駐させない。** 毎回 `podman run --rm` で起動し、`GRADLE_USER_HOME` だけ名前付きボリューム（`android-build-gradle`）に残す。
3. イメージ定義はリポジトリの `container/Containerfile` に置き、版を全て固定する（cmdline-tools 23.0 を SHA-1、Gradle 9.6.0 を SHA-256、platform 36/37.0/37.2、build-tools 36.0.0/37.0.0）。
4. adb は Hermes 側に置く。APK は scp で受け取る。

## 理由

- 入れ子は原理的に不可。設定の工夫で回避できる類ではない
- 常駐は利点が無い。キャッシュはボリュームで残り、起動は数秒。イメージ更新の反映漏れという実害だけが残る
- イメージをリポジトリに入れると、環境の再現単位が git のコミットになる

## 影響

- ビルドは SSH 越しなので対話的でない。`scripts/abuild.sh` をラッパーにする
- イメージの更新は明示的な `podman build` が要る（自動では反映されない）。これは意図した挙動
- desktop が落ちているとビルドできない。単一障害点だが、SDK を複数箇所に置くより良い

## 戻す基準

ホスト側で Hermes のコンテナに `--privileged` 相当を与える運用に変えるなら再検討する。ただしそれはホストの root を渡すことに近く、ビルド環境の分離という目的と引き換えになる。
