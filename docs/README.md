# 設計文書

hatsu-desk の設計と ADR。**このディレクトリが正**で、変更は PR でレビューする。

SilverBullet（notes.st-albireo.net）にも同じ内容の写しがある。読むのはどちらでもよいが、編集はここで行い、写しへは一方向に反映する。両方を手で直さない。

## 文書

- [設計 v0.7](design/01-ranking-deck-viewer.md) — 現在の姿。上書きして育てる
- [ビルド環境](build-environment.md) — コンテナと固定している版

## ADR

追記のみ。方針を変えるときは新しい ADR を起こし、古いものは「置換」に指定する。過去の ADR は書き換えない。

| # | 決定 |
| --- | --- |
| [0001](adr/0001-no-screenshot-share.md) | 収集の主経路にスクショ共有を用いない（一部置換） |
| [0002](adr/0002-recording-and-overlay-button.md) | 取得は画面録画とオーバーレイボタンを既定にする |
| [0003](adr/0003-support-by-combination.md) | サポートは組み合わせで集計する |
| [0004](adr/0004-document-oriented-storage.md) | 永続化は文書指向にする |
| [0005](adr/0005-build-in-rootless-podman.md) | ビルド環境はホストの rootless podman に置き、常駐させない |
| [0006](adr/0006-eval-point-as-observed.md) | 評価点は再計算せず、画面の % は保存しない |
| [0007](adr/0007-split-core-and-app.md) | モジュールを `:core` と `:app` に分ける |
| [0008](adr/0008-mlkit-bundled-japanese.md) | OCR は ML Kit の日本語 bundled を使う |
| [0009](adr/0009-card-dictionary-grown-by-capture.md) | カード辞書は同梱せず、詳細撮影で育てる |
| [0010](adr/0010-enforce-no-internet.md) | 通信権限を持たないことをビルドで強制する |

## 読み方

- 設計書は現在の姿だけを持つ。改訂の理由は冒頭の1行と ADR に置く
- ADR は「文脈・決定・理由・影響・戻す基準」で書く。戻す基準が無い決定は書かない
- 数字は実測か出典を添える。推定は推定と書く
- 図は Mermaid で書く
