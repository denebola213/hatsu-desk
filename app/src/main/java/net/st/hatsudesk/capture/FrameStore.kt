package net.st.hatsudesk.capture

/**
 * セッション世代ごとの「最新1枚」を持つ。
 *
 * 設計 §4.1 の「リングバッファは持たない」をそのまま表す。**取り出しても消えない。**
 * 保存は「保持している1枚を写し取る」だけで、バッファは空にならない。画面が静止していると
 * `VirtualDisplay` は新しいフレームを出さないことがある（実機で確認済み: 消費する作りだと
 * 2枚目以降が保存できない）。だから保存に使うのは、常に「いま届いている最新の1枚」。
 *
 * **フレームの時刻で選ばない理由。** `Image.getTimestamp()` は合成された時刻ではなく、
 * 合成済みのバッファを最後の sink へ `queueBuffer()` した時刻（`systemTime(SYSTEM_TIME_MONOTONIC)`）。
 * 隠す前に合成された絵が、隠した後にキューされると、その時刻は「隠した後」になる。
 * 時刻の比較では「隠す前の絵」を落とせないので、この判定は持たない。焼き付きは
 * 取得ボタン側を `FLAG_SECURE` にして投影の絵から外すことで断つ（`CaptureOverlayButton`）。
 *
 * **世代で分ける理由。** 停止してすぐ始め直すと、旧 `ImageReader` のコールバックが
 * 遅れて走ることがある。世代を見ずに受けると、古い世代のフレームが新しい世代の保存に混ざる。
 * 受け口は [begin] のたびに新しくなり、古い世代の [Session] へ届いたフレームは閉じて捨てる。
 *
 * 型を閉じないのは、`android.media.Image` を直接持つと JVM の単体テストで動かせないため。
 * 閉じる処理は呼出側から渡す（[close]）。フレームの持ち主はこのクラスだけで、閉じるのは
 * - 新しいフレームに置き換えられたとき（[offer]）
 * - [end] / [begin] で世代を捨てたとき（[end]）
 * - 古い世代に届いたとき（[offer]）
 * の3経路で、どれも1回だけ。呼出側は [withLatest] のブロックの中で画素を写し取るだけで、
 * `close()` を呼ばない。
 *
 * 読む側（ImageReader の HandlerThread）と保存する側（IO）が別スレッドなので同期する。
 */
internal class FrameStore<T>(
    private val close: (T) -> Unit,
) {

    /** 1回の投影セッション。世代番号は [begin] のたびに増える。 */
    class Session<T> internal constructor(val generation: Long) {

        internal var latest: T? = null
    }

    private var lastGeneration = 0L
    private var current: Session<T>? = null

    /** 新しい世代を始める。前の世代のフレームは閉じる。 */
    @Synchronized
    fun begin(): Session<T> {
        clearLocked()
        lastGeneration += 1
        return Session<T>(lastGeneration).also { current = it }
    }

    /** いまの世代。止まっていれば null。 */
    @Synchronized
    fun current(): Session<T>? = current

    /** [session] がいまの世代か。 */
    @Synchronized
    fun isCurrent(session: Session<T>): Boolean = current === session

    /**
     * [session] のフレームとして受け取る。古い世代なら閉じて捨てる（＝新しい世代の
     * 保存に使わせない）。
     */
    @Synchronized
    fun offer(session: Session<T>, frame: T): FrameOffer {
        if (current !== session) {
            close(frame)
            return FrameOffer.STALE
        }
        val wasEmpty = session.latest == null
        val previous = session.latest
        session.latest = frame
        previous?.let(close)
        return if (wasEmpty) FrameOffer.FIRST else FrameOffer.NEXT
    }

    /** いまの世代にフレームがあるか。 */
    @Synchronized
    fun hasFrame(): Boolean = current?.latest != null

    /** [session] にフレームがあるか。古い世代は「無い」。 */
    @Synchronized
    fun hasFrame(session: Session<T>): Boolean = current === session && session.latest != null

    /**
     * [session] が保持している最新フレームを、ロックを持ったまま [block] に渡す。消費しない。
     * 古い世代・フレーム無しなら null。ブロックの中では画素を写し取るだけにして、長い処理
     * （PNG 圧縮など）をしない。その間だけ新しいフレームの差し替えが待つ。
     */
    @Synchronized
    fun <R> withLatest(session: Session<T>, block: (T) -> R): R? {
        if (current !== session) return null
        val frame = session.latest ?: return null
        return block(frame)
    }

    /** いまの世代を捨てる（停止時）。以後その世代へ届いたフレームは [FrameOffer.STALE]。 */
    @Synchronized
    fun end() {
        // 先に保持しているフレームを閉じる。current を先に消すと、閉じる相手を見失って漏れる。
        clearLocked()
        current = null
    }

    private fun clearLocked() {
        val frame = current?.latest
        current?.latest = null
        frame?.let(close)
    }
}

/** [FrameStore.offer] の結果。 */
internal enum class FrameOffer {
    /** 古い世代。閉じて捨てた。 */
    STALE,

    /** 受け取った。この世代で最初の1枚。 */
    FIRST,

    /** 受け取った。2枚目以降。 */
    NEXT,
}
