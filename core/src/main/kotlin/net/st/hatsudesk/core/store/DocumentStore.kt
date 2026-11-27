package net.st.hatsudesk.core.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.st.hatsudesk.core.model.CardMaster
import net.st.hatsudesk.core.model.Run
import net.st.hatsudesk.core.model.RunDocument
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** `index/runs.idx` の1行。起動時に全部を読み直さずに一覧を出すための要約。 */
@Serializable
data class RunIndexEntry(
    @SerialName("run_id") val runId: String,
    @SerialName("ranking_id") val rankingId: String,
    @SerialName("producer_id") val producerId: String? = null,
    val rank: Int? = null,
    @SerialName("eval_point") val evalPoint: Int? = null,
    @SerialName("param_total") val paramTotal: Int? = null,
    @SerialName("idol_card_id") val idolCardId: String? = null,
    val completeness: Int? = null,
)

@Serializable
data class RunsIndex(
    val version: Int = 1,
    val entries: List<RunIndexEntry> = emptyList(),
)

/**
 * 文書指向のストア（ADR 0004 / 設計 §8.6・§11.5）。
 *
 * - `runs/<run_id>.json` … run 1件（run を根とした木を1文書にまとめる）
 * - `cards/<card_id>.json` … カード辞書1件
 * - `index/runs.idx` … 一覧用の要約。壊れても消えても `runs/` から作り直す
 *
 * 書き込みは同一ディレクトリの temp に書いて rename する。読み込みは拡張子 `.json` を
 * 全部読み、壊れた1件はスキップして残りを返す（1件の破損で一覧が消えないため）。
 */
class DocumentStore(root: Path) {

    private val root: Path = root.toAbsolutePath().normalize()
    private val runsDir: Path = this.root.resolve("runs")
    private val cardsDir: Path = this.root.resolve("cards")
    private val indexDir: Path = this.root.resolve("index")
    private val indexFile: Path = indexDir.resolve("runs.idx")

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

    init {
        Files.createDirectories(runsDir)
        Files.createDirectories(cardsDir)
        Files.createDirectories(indexDir)
    }

    // ---- runs ----

    fun saveRun(document: RunDocument) {
        val id = requireSafeId(document.run.id, "run_id")
        writeAtomically(runsDir.resolve("$id.json"), json.encodeToString(document))
        // 索引は保存のたびに作り直す。件数は 1000 程度を想定しており、全件読みでも数 ms。
        // 壊れたファイルは読み飛ばされるので、索引も自然に治る。
        rebuildIndex()
    }

    fun loadRun(id: String): RunDocument? =
        readOrNull(runsDir.resolve("${requireSafeId(id, "run_id")}.json"))

    /** `runs/` の `.json` を全部読む。壊れたファイルはスキップする。 */
    fun loadRuns(): List<RunDocument> = jsonFiles(runsDir).mapNotNull { readOrNull(it) }

    fun runFiles(): List<Path> = jsonFiles(runsDir)

    // ---- cards ----

    fun saveCard(card: CardMaster) {
        val id = requireSafeId(card.cardId, "card_id")
        writeAtomically(cardsDir.resolve("$id.json"), json.encodeToString(card))
    }

    fun loadCard(id: String): CardMaster? =
        readOrNull(cardsDir.resolve("${requireSafeId(id, "card_id")}.json"))

    fun loadCards(): List<CardMaster> = jsonFiles(cardsDir).mapNotNull { readOrNull(it) }

    // ---- index ----

    /**
     * 索引を読む。無い・壊れている・スキーマが違うときは `runs/` から作り直して返す。
     * 索引はあくまで要約で、正は `runs/` 側にある。
     */
    fun loadIndex(): List<RunIndexEntry> {
        val index = readOrNull<RunsIndex>(indexFile)
        if (index != null && index.version == CURRENT_INDEX_VERSION) return index.entries
        return rebuildIndex()
    }

    fun rebuildIndex(): List<RunIndexEntry> {
        val entries = loadRuns()
            .sortedBy { it.run.id }
            .map { document ->
                val run: Run = document.run
                RunIndexEntry(
                    runId = run.id,
                    rankingId = run.rankingId,
                    producerId = run.producerId,
                    rank = run.rank,
                    evalPoint = run.evalPoint,
                    paramTotal = run.paramTotal,
                    idolCardId = document.idol?.cardId,
                    completeness = run.completeness,
                )
            }
        writeAtomically(indexFile, json.encodeToString(RunsIndex(entries = entries)))
        return entries
    }

    // ---- internals ----

    private inline fun <reified T> readOrNull(file: Path): T? {
        if (!Files.isRegularFile(file)) return null
        return try {
            json.decodeFromString<T>(Files.readString(file))
        } catch (e: IllegalArgumentException) {
            // JSON の構文エラー・必須フィールドの欠落・型違いはここに来る
            // （SerializationException は IllegalArgumentException の派生）。
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun jsonFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.newDirectoryStream(dir, "*.json").use { stream ->
            return stream.toList()
                .filter { Files.isRegularFile(it) }
                .sortedBy { it.fileName.toString() }
        }
    }

    private fun writeAtomically(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.writeString(temp, content)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                // temp と target が同一ディレクトリなので通常は起きないが、環境次第で落とさない。
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun requireSafeId(id: String, field: String): String {
        require(id.isNotBlank()) { "$field が空" }
        require(!id.contains('/') && !id.contains('\\') && id != "." && id != "..") {
            "$field にファイル名として使えない文字が入っている: $id"
        }
        return id
    }

    private companion object {
        const val CURRENT_INDEX_VERSION = 1
    }
}
