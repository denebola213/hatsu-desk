package net.st.hatsudesk.ui

import java.io.IOException

/**
 * 例外から**画面に出す日本語だけ**を作る。
 *
 * 例外型名・`message`（OS が英語で返すことがある）はここに混ぜない。調査に要る情報は
 * 呼出側（`Common.kt` の [describeFailure]）が `android.util.Log` に残す。
 *
 * `android.util.Log` を参照しないので、`:app` の JVM 単体テストからそのまま呼べる。
 */
fun userMessageFor(error: Throwable): String = when (error) {
    is IOException ->
        "端末の保存領域を読み書きできませんでした。空き容量を確かめてください。"
    is SecurityException ->
        "保存領域へのアクセスが拒否されました。端末の設定を確かめてください。"
    else ->
        "予期しない失敗です。もう一度試してください。"
}
