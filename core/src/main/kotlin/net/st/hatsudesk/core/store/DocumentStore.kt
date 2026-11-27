package net.st.hatsudesk.core.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.st.hatsudesk.core.model.CardMaster
import net.st.hatsudesk.core.model.Run
import net.st.hatsudesk.core.model.RunDocument
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

/**
 * `index/runs.idx` のディスク形式。:app に見せる必要はないので internal。
 *
 * 既定値を持たせない。既定値があると `encodeDefaults = false`（既定）で `version` が
 * 出力されず、`{}` を「版1・空」として受理してしまう。必須にしておけば、版の無い
 * JSON はデコード時に落ちて `runs/` からの再生成に回る。
 */
@Serializable
internal data class RunsIndex(
    val version: Int,
    val entries: List<RunIndexEntry>,
)

/**
 * 文書指向のストア（ADR 0004 / 設計 §8.6・§11.5）。
 *
 * - `runs/<run_id>.json` … run 1件（run を根とした木を1文書にまとめる）
 * - `cards/<card_id>.json` … カード辞書1件
 * - `index/runs.idx` … 一覧用の要約。壊れても消えても `runs/` から作り直す
 *
 * 書き込みは同一ディレクトリの temp に書いて rename する。読み込みは拡張子 `.json` を
 * 全部読む。壊れた1件（構文・スキーマ違反）はスキップして残りを返す（1件の破損で
 * 一覧が消えないため）。読めないファイル（I/O 障害）はスキップせず [IOException] を
 * そのまま投げる。読めなかった run があるのに不完全な索引を正常として確定しないため。
 *
 * 文字コードは UTF-8 に固定し、置換文字で黙って通さない。不正なバイト列（例: `c3 28`）
 * や孤立サロゲートは、置換（U+FFFD / `0x3f`）ではなく [java.nio.charset.CharacterCodingException]
 * にする。これは [IOException] の派生なので「I/O 障害は失敗」の経路に乗る。符号化の破損を
 * 構文エラー扱いでスキップすると、壊れた文書を正常な一覧に混ぜてしまうため。
 *
 * **スレッド安全。** 書き込み（run / card の保存）と索引の再生成・読み込みは、単一の
 * [ReentrantLock] で直列化する（設計 §11.5 の Mutex。単一プロセス前提）。これにより
 * 並行する [saveRun] が「相手の run を見ていない古い索引」で新しい索引を上書きすることは
 * ない。[loadRun] / [loadRuns] はロックしない。各ファイルは rename で原子的に置き換わる
 * ので、読む側は常に「置き換え前」か「置き換え後」の完全な内容を見る。
 */
class DocumentStore(root: Path) {

    private val root: Path = root.toAbsolutePath().normalize()
    private val runsDir: Path = this.root.resolve("runs")
    private val cardsDir: Path = this.root.resolve("cards")
    private val indexDir: Path = this.root.resolve("index")
    private val indexFile: Path = indexDir.resolve("runs.idx")

    /** 書き込み（run 保存＋索引再生成）と索引読み込みを直列化する。再入可能。 */
    private val writeLock = ReentrantLock()

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
        writeLock.withLock {
            writeAtomically(runsDir.resolve("$id.json"), json.encodeToString(document))
            // 索引は保存のたびに作り直す。件数は 1000 程度を想定しており、全件読みでも数 ms。
            // 壊れたファイルは読み飛ばされるので、索引も自然に治る。
            rebuildIndexLocked()
        }
    }

    fun loadRun(id: String): RunDocument? =
        readOrNull(runsDir.resolve("${requireSafeId(id, "run_id")}.json"))

    /**
     * `runs/` の `.json` を全部読む。構文・スキーマが壊れたファイルはスキップする。
     * 読めないファイル（I/O 障害）は [IOException] を投げ、不完全な一覧を返さない。
     */
    fun loadRuns(): List<RunDocument> = jsonFiles(runsDir).mapNotNull { readOrNull(it) }

    // ---- cards ----

    fun saveCard(card: CardMaster) {
        val id = requireSafeId(card.cardId, "card_id")
        writeLock.withLock {
            writeAtomically(cardsDir.resolve("$id.json"), json.encodeToString(card))
        }
    }

    fun loadCard(id: String): CardMaster? =
        readOrNull(cardsDir.resolve("${requireSafeId(id, "card_id")}.json"))

    fun loadCards(): List<CardMaster> = jsonFiles(cardsDir).mapNotNull { readOrNull(it) }

    // ---- index ----

    /**
     * 索引を読む。無い・壊れている・スキーマが違う・版が違うときは `runs/` から作り直して返す。
     * 索引はあくまで要約で、正は `runs/` 側にある。
     */
    fun loadIndex(): List<RunIndexEntry> = writeLock.withLock {
        val index = readOrNull<RunsIndex>(indexFile)
        if (index != null && index.version == CURRENT_INDEX_VERSION) index.entries
        else rebuildIndexLocked()
    }

    /** 索引を作り直して書き戻す。呼出側が [writeLock] を保持していること。 */
    private fun rebuildIndexLocked(): List<RunIndexEntry> =
        try {
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
            writeAtomically(
                indexFile,
                json.encodeToString(RunsIndex(version = CURRENT_INDEX_VERSION, entries = entries)),
            )
            entries
        } catch (e: IOException) {
            // 不完全な要約を「有効な索引」として残さない。次回の loadIndex は再生成を試み、
            // 障害が続く限り同じ IOException で失敗する（黙って古い一覧を返さない）。
            runCatching { Files.deleteIfExists(indexFile) }
            throw e
        }

    // ---- internals ----

    /**
     * `file` を読んでデコードする。ファイルが無ければ null、JSON の構文・スキーマ違反も
     * null（1件の破損で他が読めなくならないように）。I/O 障害は握りつぶさず伝播させる。
     * 不正な UTF-8 は [java.nio.charset.CharacterCodingException]（[IOException] の派生）
     * として伝播する。構文エラーと同じ null にすると、壊れた文書を黙って一覧から外すだけになる。
     */
    private inline fun <reified T> readOrNull(file: Path): T? {
        val text = try {
            decodeUtf8(Files.readAllBytes(file))
        } catch (e: NoSuchFileException) {
            return null
        }
        return try {
            json.decodeFromString<T>(text)
        } catch (e: IllegalArgumentException) {
            // JSON の構文エラー・必須フィールドの欠落・型違いはここに来る
            // （SerializationException は IllegalArgumentException の派生）。
            null
        }
    }

    /**
     * `*.json` を列挙する。`isRegularFile` では絞らない。ディレクトリなど読めない
     * エントリを黙って落とすと、[loadRuns] が不完全な一覧を正常として返してしまう。
     */
    private fun jsonFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.newDirectoryStream(dir, "*.json").use { stream ->
            return stream.toList().sortedBy { it.fileName.toString() }
        }
    }

    private fun writeAtomically(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            // API 26 の Files には文字列を直接書くメソッドが無い（ADR 0011）。JDK 版の既定と
            // 同じ CREATE + TRUNCATE_EXISTING + WRITE で、createTempFile が作った空ファイルに
            // 上書きする。符号化は先に済ませ、失敗したら temp も target も触らない。
            Files.write(
                temp,
                encodeUtf8(content),
                CREATE,
                TRUNCATE_EXISTING,
                WRITE,
            )
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

    /**
     * UTF-8 を厳密にデコードする。置換はしない（[CodingErrorAction.REPORT]）。
     *
     * `String(bytes, UTF_8)` は不正なバイト列を U+FFFD に置き換えて返すため、
     * JDK 17 の `Files` にある文字列読み取り版（置換せず `MalformedInputException`）と
     * 振る舞いが変わる。JSON の構文エラーに見えてしまい、
     * 「壊れた文書はスキップ」に吸い込まれる。ここでは [java.nio.charset.CharacterCodingException]
     * （[IOException] の派生）にして、I/O 障害として失敗させる。
     *
     * 使用 API はいずれも android-36 の `api-versions.xml` で `since` 1（[StandardCharsets] は 19）。
     */
    private fun decodeUtf8(bytes: ByteArray): String =
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /**
     * UTF-8 を厳密にエンコードする。置換はしない（[CodingErrorAction.REPORT]）。
     *
     * `String.toByteArray(UTF_8)` は孤立サロゲートを `0x3f`（`?`）に置き換えるため、
     * JDK 17 の `Files` にある文字列書き込み版と振る舞いが変わる。あちらは置換せず
     * `UnmappableCharacterException` を投げ、こちらは同じ入力に `MalformedInputException`
     * を投げる（どちらも `CharacterCodingException` = [IOException] の派生で、置換はしない）。
     * 壊れた文字列が黙って別の値として保存されないよう、例外にする。
     */
    private fun encodeUtf8(text: String): ByteArray {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        // encode が返すバッファは position が 0、limit が符号化結果の末尾。
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return bytes
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
