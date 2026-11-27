package net.st.hatsudesk.capture

/**
 * 1回の取得で、何を待ち、保存してよいかを決める。
 *
 * 待つのは時間ではなくフレームの到着だが、**待つかどうかは「隠すボタンがあったか」で変わる**。
 *
 * - 隠すものが無い（オーバーレイ未許可）: 待たずに、いま保持している最新1枚を使う。
 *   静止した画面では `VirtualDisplay` が新しいフレームを出さないので、待つと必ず失敗する。
 * - 隠した: 隠した後に届いたフレームが [FreshFrameGate.REQUIRED_FRAMES] 枚以上になるまで待つ。
 *   1枚で妥協すると、その1枚が「隠す前に生成され、キューに残っていた」ボタン入りの
 *   フレームである可能性を排除できない。上限まで待っても足りなければ**保存しない**
 *   （ボタン入りの1枚を残すより、失敗させて押し直してもらう）。
 *
 * 判定だけを持つ。実際に隠す・待つ・写し取るのは `CaptureController`。
 */
internal class CapturePlan private constructor(
    /** 押した時点の通し番号。 */
    val pressSerial: Long,
    /** 隠すボタンがあったか。無ければフレームの到着を待たない。 */
    val hidesOverlay: Boolean,
    private val gate: FreshFrameGate,
) {

    /** フレームの到着を待つか。 */
    val waitsForFrames: Boolean get() = hidesOverlay

    /** 通し番号が [serial] まで進んだ時点の判定。 */
    fun outcome(serial: Long): Outcome = when {
        !hidesOverlay -> Outcome.LATEST
        gate.isReady(serial) -> Outcome.FRESH
        else -> Outcome.TOO_FEW
    }

    enum class Outcome {
        /** 待たずに、保持している最新1枚を保存してよい。 */
        LATEST,

        /** 隠した後に届いたフレームを保存してよい。 */
        FRESH,

        /** 隠した後に十分な枚数が来なかった。**保存してはいけない。** */
        TOO_FEW,
    }

    companion object {
        /** 隠した後にフレームが来ないとき、画面に出す一言。 */
        const val STALLED_MESSAGE = "画面が止まっていて撮れませんでした。もう一度押してください"

        /**
         * 押した時点の段取りを決める。隠すときは、その時点の通し番号を [gate] に渡して
         * 「ここから何枚進んだか」を数えられるようにする。
         */
        fun forPress(
            pressSerial: Long,
            hidesOverlay: Boolean,
            gate: FreshFrameGate,
        ): CapturePlan {
            if (hidesOverlay) gate.after(pressSerial)
            return CapturePlan(pressSerial, hidesOverlay, gate)
        }
    }
}
