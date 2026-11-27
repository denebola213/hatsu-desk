package net.st.hatsudesk.capture

import android.media.Image

/**
 * 最新の1枚だけを持つ。設計 §4.1 の「リングバッファは持たない」をそのまま表す。
 *
 * **取り出しても消えない。** 保存は「保持している1枚を写し取る」だけで、バッファは空に
 * ならない。画面が静止していると `VirtualDisplay` は新しいフレームを出さないことがある
 * （実機で確認済み: 消費する作りだと2枚目以降が保存できない）。
 *
 * [Image] の持ち主はこのクラスだけ。閉じるのは
 * - 新しいフレームに置き換えられたとき（[offer]）
 * - [clear] されたとき
 * の2経路で、どちらも1回だけ。呼出側は [withLatest] のブロックの中で画素を写し取るだけで、
 * `close()` を呼ばない。
 *
 * 読む側（ImageReader の HandlerThread）と保存する側（IO）が別スレッドなので同期する。
 */
class LatestFrameBuffer {

    private var latest: Image? = null

    /** 新しいフレームを置く。置き換えたフレームは閉じる。 */
    @Synchronized
    fun offer(image: Image) {
        val previous = latest
        latest = image
        previous?.close()
    }

    /**
     * 保持している最新フレームを、ロックを持ったまま [block] に渡す。消費しない。
     * フレームが無ければ null。ブロックの中では画素を写し取るだけにして、長い処理
     * （PNG 圧縮など）をしない。その間だけ新しいフレームの差し替えが待つ。
     */
    @Synchronized
    fun <R> withLatest(block: (Image) -> R): R? {
        val image = latest ?: return null
        return block(image)
    }

    /** 保持しているフレームを捨てる（停止時）。 */
    @Synchronized
    fun clear() {
        val image = latest
        latest = null
        image?.close()
    }

    @Synchronized
    fun hasFrame(): Boolean = latest != null
}
