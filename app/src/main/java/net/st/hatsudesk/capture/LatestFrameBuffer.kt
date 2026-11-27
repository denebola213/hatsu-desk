package net.st.hatsudesk.capture

import android.media.Image

/**
 * 最新の1枚だけを持つ。設計 §4.1 の「リングバッファは持たない」をそのまま表す。
 *
 * [Image] は閉じる責任を持つ側を1つに決める。ここに来た [Image] は、
 * - 新しいフレームに置き換えられたら [offer] が閉じる
 * - 取り出されたら [take] の呼出側が閉じる（[CaptureController] が必ず `finally` で閉じる）
 * - [clear] されたら閉じる
 *
 * どの経路でも1回しか閉じない。閉じ忘れ（`ImageReader` の maxImages を食い潰して
 * フレームが来なくなる）を残さないため、`close()` をここ以外に散らかさない。
 *
 * 読む側（HandlerThread）と保存する側（IO）が別スレッドなので同期する。
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

    /** 最新の1枚を取り出す。取り出した [Image] は呼出側が閉じる。無ければ null。 */
    @Synchronized
    fun take(): Image? {
        val image = latest
        latest = null
        return image
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
