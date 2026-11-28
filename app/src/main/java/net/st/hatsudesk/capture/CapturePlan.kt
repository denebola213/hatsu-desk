package net.st.hatsudesk.capture

/** 取得の待ち時間。実機時計の代わりに `SystemClock.elapsedRealtime()` の値を渡して判定する。 */
internal class CaptureDeadline(startedAtMillis: Long) {
    private val expiresAtMillis = startedAtMillis + TIMEOUT_MS

    /** 残り時間。600ms に達したら 0 を返し、Controller は保存せずに中止する。 */
    fun remainingMillis(nowMillis: Long): Long =
        (expiresAtMillis - nowMillis).coerceAtLeast(0L)

    companion object {
        const val TIMEOUT_MS = 600L
    }
}

/**
 * 1回の取得で、何を待ち、どのフレームを保存してよいかを決める。
 *
 * 待つのは**隠し終えた時刻より後にキューされたフレームの到着**で、枚数ではない。
 * `ImageReader` のコールバックは隠す処理と同期しないため、「何枚届いたか」では
 * 隠す前に合成されたフレームを数えてしまう。
 *
 * **ただし時刻は一次フィルタにすぎない。** `Image.getTimestamp()` は合成時刻ではなく
 * 最後の sink への `queueBuffer()` の時刻なので、隠す前に合成された絵が隠した後に
 * キューされれば「隠した後」の時刻を持つ。この判定を通った候補でも、ボタンが写っている
 * ことがある。保存してよいかの最終判断は `OverlayPixelCheck`（`VerifiedFrameGate` 経由）が、
 * 候補の矩形に残る黒い印から行う。
 *
 * 時刻はミリ秒に丸めない。`System.nanoTime()` は `SystemClock.uptimeMillis()` と同じ
 * 単調時計（CLOCK_MONOTONIC）で、`Image.getTimestamp()` の基準でもある。ms に丸めると、
 * 同じミリ秒の中で hide 完了前に合成されたフレームを採る隙ができる。
 *
 * 条件を満たす**最初の1枚**を候補にする。2枚目以降は、画素で駄目だったときだけ見る。
 *
 * 隠すボタンが無い（オーバーレイ未許可）ときは待たない。静止した画面では
 * `VirtualDisplay` が新しいフレームを出さないので、待つと必ず失敗する。
 *
 * 判定だけを持つ。実際に隠す・待つ・写し取るのは `CaptureController`。
 */
internal class CapturePlan private constructor(
    /**
     * 隠し終えた時刻。フレームの時刻と同じ単調時計のナノ秒。
     * null は「隠すボタンが無い」＝待たずに最新1枚を使う。
     */
    val hiddenAtNanos: Long?,
) {

    /** フレームの到着を待つか。 */
    val waitsForFrames: Boolean get() = hiddenAtNanos != null

    /**
     * [timestampNanos] に合成されたフレームを保存に使ってよいか。
     * 隠すボタンが無いときは何でもよい（隠す前の絵という概念が無い）。
     */
    fun accepts(timestampNanos: Long): Boolean {
        val from = hiddenAtNanos ?: return true
        return timestampNanos >= from
    }

    companion object {
        /** 黒い印が消えないとき・画面が止まっているときに、人へ次の操作を示す一言。 */
        const val STALLED_MESSAGE = "画面が止まっているか、ボタンの下が真っ黒です。ボタンを動かして再度お試しください"

        /**
         * 押した時点の段取りを決める。
         *
         * @param hideCompletedAtNanos [CaptureOverlayButton.hide] が true を返した
         *   直後の `System.nanoTime()`。null は隠すボタンが無かった場合。
         */
        fun forPress(hideCompletedAtNanos: Long?): CapturePlan = CapturePlan(hideCompletedAtNanos)
    }
}
