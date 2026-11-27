package net.st.hatsudesk.capture

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 保存済みの枚数をディスクから読み直す順序を守る。
 *
 * 読み直しは呼ぶたびにコルーチンを起こす。`Dispatchers.IO` の `list()` から反映までの間に
 * 保存の反映や次の読み直しが追い越せるので、先に始まった古い一覧をそのまま書くと
 * 枚数が古い値へ戻る（N 枚を読んだ後に N+1 枚が反映され、最後に N 枚で上書きされる）。
 *
 * そこで要求ごとに世代番号を振り、反映の直前に「自分より新しい世代が始まっていないか」を
 * 確かめる。[record] は保存1枚の反映で、世代を進める。保存より前に始まった読み直しは
 * 後から枚数を戻せない。
 *
 * [refresh] / [record] / [apply] は同じ1本のスレッド（`CaptureController` では Main）から
 * 呼ばれる前提。`apply` の中で状態を書く側が、その前提に合わせて排他を持つ。
 */
internal class SavedCountRefresher(
    private val scope: CoroutineScope,
    private val read: suspend () -> Int,
    private val apply: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {

    private val generation = AtomicLong()

    /**
     * 読み直しを1回頼む。返す [Job] は、完了を待ちたい呼出側（とテスト）のため。
     * 反映されるのは、この要求より新しい要求が始まっていない場合だけ。
     */
    fun refresh(): Job {
        val token = generation.incrementAndGet()
        return scope.launch {
            val count = try {
                withContext(Dispatchers.IO) { read() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                onError(error)
                return@launch
            }
            if (generation.get() != token) return@launch
            apply(count)
        }
    }

    /**
     * 保存が1枚増えたことを反映する。[total] は書き込みが終わった時点の実数。
     *
     * 加算ではなく実数を受け取るのは、読み直しが同じファイルを数えていても
     * 二重に増やさないため。世代を進めるので、これより前に始まった読み直しは捨てられる。
     */
    fun record(total: Int) {
        generation.incrementAndGet()
        apply(total)
    }
}
