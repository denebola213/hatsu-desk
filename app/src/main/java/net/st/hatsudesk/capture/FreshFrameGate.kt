package net.st.hatsudesk.capture

/**
 * ボタンを隠した後、どのフレームから「焼き付いていない」と言えるかを決める。
 *
 * 設計 §4.1 の「押下時のフレームを先に確保してから隠す」では焼き付きは消えない。
 * バッファの最新フレームにはすでにボタンが合成されているためで、必要なのは
 * **隠した後に届いたフレーム**を待つこと。固定の待ち時間にしないのは、待つべきなのが
 * 時間ではなくフレームの到着だから（短ければ数十 ms で済み、遅い端末でも取りこぼさない）。
 *
 * 通し番号は [FrameStore] に届いた順で、フレームが1枚増えるごとに +1。
 *
 * 判定は必ず**差分**で行う。`baseline + required` で比べると、通し番号が Long の上限に
 * 近いときに負数へ回り込み、新しいフレームが1枚も来なくても「届いた」と答えてしまう。
 */
class FreshFrameGate(private val required: Int = REQUIRED_FRAMES) {

    /** 隠す直前の通し番号。まだ [after] を呼んでいなければ null。 */
    private var baseline: Long? = null

    /** 隠す直前の通し番号を覚える。待つべき枚数は [isReady] が差分で決める。 */
    fun after(serial: Long) {
        baseline = serial
    }

    /** 隠した後のフレームが [required] 枚以上届いたか。 */
    fun isReady(serial: Long): Boolean = arrived(serial) >= required

    /**
     * 隠した後に1枚でも届いたか。**保存の条件には使わない。** 1枚だけでは、それが
     * 隠す前に生成されてキューに残っていたボタン入りのフレームである可能性を
     * 排除できない（[isReady] の「2枚」がそのための条件）。
     */
    fun hasAnyNewFrame(serial: Long): Boolean = arrived(serial) > 0

    /** [after] してから届いた枚数。まだ [after] していなければ「来ていない」に倒す。 */
    private fun arrived(serial: Long): Long {
        val from = baseline ?: return -1
        return serial - from
    }

    companion object {
        /** 隠した後、合成が追いついたと見なす枚数。 */
        const val REQUIRED_FRAMES = 2
    }
}
