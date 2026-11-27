package net.st.hatsudesk.core.store

import java.util.concurrent.locks.ReentrantLock

/**
 * ストア全体の書き込みロック（設計 §11.5 の Mutex。単一プロセス前提）。
 *
 * [DocumentStore] と [CaptureStore] が同じロックを共有する。run の JSON と PNG は別の
 * ディレクトリに落ちるが、直列化の単位を「ストア全体」にしておくと、書き込みが増えても
 * ロックの取り方が1通りで済む。再入可能なので、[DocumentStore] が索引の再生成で
 * 入れ子に取っても詰まらない。
 */
internal object StoreWriteLock {
    val lock: ReentrantLock = ReentrantLock()
}
