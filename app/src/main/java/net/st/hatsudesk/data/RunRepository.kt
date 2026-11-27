package net.st.hatsudesk.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.st.hatsudesk.core.model.CardMaster
import net.st.hatsudesk.core.model.RunDocument
import net.st.hatsudesk.core.store.DocumentStore
import net.st.hatsudesk.core.store.RunIndexEntry
import java.io.File

/** 一覧に出す要約と、名前解決に使うカード辞書。 */
data class RunOverview(
    val entries: List<RunIndexEntry>,
    val cards: Map<String, CardMaster>,
)

/** 構成詳細に出す run 1件と、名前解決に使うカード辞書。 */
data class RunDetailData(
    val document: RunDocument,
    val cards: Map<String, CardMaster>,
)

/** カード辞書の閲覧用。登録済みと、構成に出てくるが未登録の ID。 */
data class DictionaryData(
    val cards: List<CardMaster>,
    val unknownCardIds: List<String>,
)

/**
 * `:core` の [DocumentStore] を画面から使うための薄い層。
 *
 * 保存先は `context.filesDir` の直下で、[DocumentStore] が `runs/`・`cards/`・`index/` を
 * その下に作る（設計 §8.6・§11.5）。読み書きは I/O スレッドで行う。
 *
 * [DocumentStore] の `loadRuns` / `loadIndex` / `loadCards` は I/O 障害で [java.io.IOException]
 * を投げる。ここでは握り潰さずそのまま呼出側（画面）へ伝え、画面が失敗として表示する。
 */
class RunRepository(filesDir: File) {

    private val filesDir: File = filesDir

    // 生成時に runs/ などを作るので、最初の利用（I/O スレッド上）まで遅らせる。
    private val store: DocumentStore by lazy { DocumentStore(filesDir.toPath()) }

    suspend fun loadOverview(): RunOverview = withContext(Dispatchers.IO) {
        RunOverview(
            entries = store.loadIndex(),
            cards = store.loadCards().associateBy { it.cardId },
        )
    }

    suspend fun loadDetail(runId: String): RunDetailData? = withContext(Dispatchers.IO) {
        val document = store.loadRun(runId) ?: return@withContext null
        RunDetailData(document = document, cards = store.loadCards().associateBy { it.cardId })
    }

    suspend fun loadDictionary(): DictionaryData = withContext(Dispatchers.IO) {
        val cards = store.loadCards()
        val known = cards.map { it.cardId }.toSet()
        val referenced = store.loadRuns()
            .flatMap { it.referencedCardIds() }
            .distinct()
            .sorted()
        DictionaryData(
            cards = cards.sortedBy { it.cardId },
            unknownCardIds = referenced.filterNot { it in known },
        )
    }

    suspend fun save(document: RunDocument) = withContext(Dispatchers.IO) {
        store.saveRun(document)
    }
}

/** 構成に出てくるカード ID。アイテムはカード辞書の対象外なので含めない。 */
private fun RunDocument.referencedCardIds(): List<String> = buildList {
    idol?.cardId?.let(::add)
    supports.forEach { support -> support.cardId?.let(::add) }
    memories.forEach { memory -> memory.cardId?.let(::add) }
    skills.forEach { skill -> skill.cardId?.let(::add) }
}
