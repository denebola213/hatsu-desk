package net.st.hatsudesk.capture

/**
 * 1回の取得で、何を待ち、どのフレームを保存してよいかを決める。
 *
 * 待つのは**隠し終えた時刻より後に生成されたフレームの到着**で、枚数ではない。
 * `ImageReader` のコールバックは隠す処理と同期しないため、「何枚届いたか」では
 * 隠す前に合成されたフレームを数えてしまう。フレームが合成された時刻
 * （`Image.getTimestamp()`）と、[CaptureOverlayButton.hide] が完了した時刻を
 * 同じ単調時計で比べれば、隠す前に生成されたフレームは必ず落ちる。
 *
 * 時刻はミリ秒に丸めない。`System.nanoTime()` は `SystemClock.uptimeMillis()` と同じ
 * 単調時計（CLOCK_MONOTONIC）で、`Image.getTimestamp()` の基準でもある。ms に丸めると、
 * 同じミリ秒の中で hide 完了前に合成されたフレームを採る隙ができる。
 *
 * 条件を満たす**最初の1枚**で撮る。2枚目は待たない（待つほど押下時から絵がずれる）。
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
        /** 隠した後にフレームが来ないとき、画面に出す一言。 */
        const val STALLED_MESSAGE = "画面が止まっていて撮れませんでした。もう一度押してください"

        /**
         * 押した時点の段取りを決める。
         *
         * @param hideCompletedAtNanos [CaptureOverlayButton.hide] が true を返した
         *   直後の `System.nanoTime()`。null は隠すボタンが無かった場合。
         */
        fun forPress(hideCompletedAtNanos: Long?): CapturePlan = CapturePlan(hideCompletedAtNanos)
    }
}
