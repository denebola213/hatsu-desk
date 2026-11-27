package net.st.hatsudesk.capture

/**
 * ボタンを隠した後、どのフレームから「焼き付いていない」と言えるかを決める。
 *
 * 設計 §4.1 の「押下時のフレームを先に確保してから隠す」では焼き付きは消えない。
 * バッファの最新フレームにはすでにボタンが合成されているためで、必要なのは
 * **隠した後に届いたフレーム**を待つこと。固定の待ち時間にしないのは、待つべきなのが
 * 時間ではなくフレームの到着だから（短ければ数十 ms で済み、遅い端末でも取りこぼさない）。
 *
 * 通し番号は [LatestFrameBuffer] に届いた順で、フレームが1枚増えるごとに +1。
 */
class FreshFrameGate(private val required: Int = REQUIRED_FRAMES) {

    /** 隠す直前の通し番号。まだ [after] を呼んでいなければ [Long.MAX_VALUE]。 */
    private var baseline: Long = Long.MAX_VALUE

    /** 隠す直前の通し番号を受け取り、待つべき番号を返す。 */
    fun after(serial: Long): Long {
        baseline = serial
        return serial + required
    }

    /** 隠した後のフレームが [required] 枚以上届いたか。 */
    fun isReady(serial: Long): Boolean = serial >= baseline + required

    /**
     * 隠した後に1枚でも届いたか。2枚待てなかったときの緩い条件で、
     * false なら手元のフレームはボタンが写ったまま（＝保存してはいけない）。
     */
    fun hasAnyNewFrame(serial: Long): Boolean = serial > baseline

    companion object {
        /** 隠した後、合成が追いついたと見なす枚数。 */
        const val REQUIRED_FRAMES = 2
    }
}
