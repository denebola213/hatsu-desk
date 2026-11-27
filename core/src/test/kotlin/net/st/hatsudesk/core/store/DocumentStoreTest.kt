package net.st.hatsudesk.core.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.st.hatsudesk.core.model.CardMaster
import net.st.hatsudesk.core.model.Run
import net.st.hatsudesk.core.model.RunChallengeItem
import net.st.hatsudesk.core.model.RunDocument
import net.st.hatsudesk.core.model.RunFinalItem
import net.st.hatsudesk.core.model.RunIdol
import net.st.hatsudesk.core.model.RunMemory
import net.st.hatsudesk.core.model.RunSkill
import net.st.hatsudesk.core.model.RunSupport
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStoreTest {

    private val tempDirs = mutableListOf<Path>()

    /** ストアの root を作る。テストは root から `runs/` や `index/` を直接見る。 */
    private fun newRoot(): Path {
        val dir = Files.createTempDirectory("hatsudesk-store-")
        tempDirs.add(dir)
        return dir
    }

    private fun newStore(root: Path = newRoot()): DocumentStore = DocumentStore(root)

    private fun runsDir(root: Path): Path = root.resolve("runs")

    private fun indexFile(root: Path): Path = root.resolve("index/runs.idx")

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { dir ->
            if (!Files.exists(dir)) return@forEach
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempDirs.clear()
    }

    private fun sampleRun(id: String, evalPoint: Int?): RunDocument = RunDocument(
        run = Run(
            id = id,
            rankingId = "ranking-1",
            producerId = "producer-1",
            sessionId = "session-1",
            rank = 3,
            plv = 42,
            grade = "S4",
            rankCap = "S4",
            evalPoint = evalPoint,
            paramTotal = 3120,
            badge = "L2",
            playedOn = "2026-11-20",
            completeness = 2,
            note = "テスト",
        ),
        idol = RunIdol(
            runId = id,
            cardId = "idol-1",
            charId = "char-1",
            rarity = "SSR",
            vo = 1200,
            da = 980,
            vi = 940,
            stamina = 88,
            star = 4,
            limitBreak = 2,
        ),
        supports = listOf(
            RunSupport(runId = id, slot = 0, cardId = "support-1", level = 50, training = 4, rental = false),
            RunSupport(runId = id, slot = 1, cardId = null, rental = true),
        ),
        memories = listOf(RunMemory(runId = id, slot = 0, cardId = "memory-1", rental = false)),
        challengeItems = listOf(
            RunChallengeItem(runId = id, slot = 0, itemId = "item-1", lessonCapBonusPct = 30),
        ),
        finalItems = listOf(RunFinalItem(runId = id, slot = 0, itemId = "item-2", tag = "lesson")),
        skills = listOf(
            RunSkill(
                runId = id,
                idx = 0,
                cardId = "skill-1",
                enhanceFrom = 1,
                enhanceTo = 2,
                cost = 12,
                costMod = -1,
                attr = "vo",
                tag = "lesson",
            ),
        ),
    )

    @Test
    fun `保存して読み戻すと同値`() {
        val root = newRoot()
        val store = newStore(root)
        val document = sampleRun("run-1", evalPoint = 15400)

        store.saveRun(document)

        assertEquals(document, store.loadRun("run-1"))
        assertEquals(listOf(document), store.loadRuns())
        val file = runsDir(root).resolve("run-1.json")
        assertTrue(Files.isRegularFile(file))
        assertEquals("run-1.json", file.fileName.toString())
    }

    @Test
    fun `保存は temp を残さず runs 直下の json だけになる`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        store.saveRun(sampleRun("run-2", evalPoint = 200))

        val names = Files.newDirectoryStream(runsDir(root)).use { stream ->
            stream.map { it.fileName.toString() }.sorted()
        }
        assertEquals(listOf("run-1.json", "run-2.json"), names)
    }

    @Test
    fun `壊れた JSON が1件あっても他の run は読める`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        store.saveRun(sampleRun("run-2", evalPoint = 200))

        val broken = runsDir(root).resolve("run-3.json")
        Files.writeString(broken, "{ \"run\": { \"id\": \"run-3\" ")

        val loaded = store.loadRuns().map { it.run.id }.sorted()
        assertEquals(listOf("run-1", "run-2"), loaded)
        assertNull(store.loadRun("run-3"))
        // 壊れたファイルは消さない（人間が中身を見て直せるように残す）。
        assertTrue(Files.exists(broken))
        assertFalse(Files.readString(broken).isEmpty())
    }

    @Test
    fun `未知のフィールドがあっても読める`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        val file = runsDir(root).resolve("run-1.json")
        val body = Files.readString(file).replaceFirst("{", "{ \"future_field\": 1,")
        Files.writeString(file, body)

        assertEquals("run-1", store.loadRun("run-1")?.run?.id)
    }

    @Test
    fun `生成した index には版と entries が必ず書かれる`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        store.saveRun(sampleRun("run-2", evalPoint = 200))

        val text = Files.readString(indexFile(root))
        val index = Json.parseToJsonElement(text).jsonObject

        assertEquals(1, index["version"]?.jsonPrimitive?.int)
        assertEquals(2, (index["entries"] as JsonArray).size)
        assertTrue(text.contains("\"version\""), "版が JSON に出力されている: $text")
    }

    @Test
    fun `版の無い index は runs から作り直す`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))

        // version も entries も無い JSON。既定値で「版1・空」として受理してはいけない。
        Files.writeString(indexFile(root), "{}")

        val entries = store.loadIndex()

        assertEquals(listOf("run-1"), entries.map { it.runId })
        val rewritten = Json.parseToJsonElement(Files.readString(indexFile(root))).jsonObject
        assertEquals(1, rewritten["version"]?.jsonPrimitive?.int)
        assertEquals(1, (rewritten["entries"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `違う版の index は runs から作り直す`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        Files.writeString(indexFile(root), "{\"version\": 999, \"entries\": []}")

        val entries = store.loadIndex()

        assertEquals(listOf("run-1"), entries.map { it.runId })
        val rewritten = Json.parseToJsonElement(Files.readString(indexFile(root))).jsonObject
        assertEquals(1, rewritten["version"]?.jsonPrimitive?.int)
        assertEquals(listOf("run-1"), store.loadIndex().map { it.runId })
    }

    @Test
    fun `index を消しても runs から再生成できる`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        store.saveRun(sampleRun("run-2", evalPoint = 300))

        assertTrue(Files.exists(indexFile(root)))
        Files.delete(indexFile(root))

        val entries = store.loadIndex()

        assertEquals(listOf("run-1", "run-2"), entries.map { it.runId })
        assertEquals(listOf(100, 300), entries.map { it.evalPoint })
        assertEquals(listOf("idol-1", "idol-1"), entries.map { it.idolCardId })
        assertTrue(Files.exists(indexFile(root)), "再生成した索引が書き戻されている")
    }

    @Test
    fun `壊れた index も runs から再生成できる`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        Files.writeString(indexFile(root), "not json at all")

        val entries = store.loadIndex()

        assertEquals(listOf("run-1"), entries.map { it.runId })
        assertEquals("run-1", store.loadRun(store.loadIndex().single().runId)?.run?.id)
    }

    @Test
    fun `読めない run は IOException になり不完全な index を確定しない`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        assertTrue(Files.exists(indexFile(root)))

        // Linux ではディレクトリの readString が IOException になる。
        Files.createDirectory(runsDir(root).resolve("bad.json"))

        assertFailsWith<IOException> { store.loadRun("bad") }
        assertFailsWith<IOException> { store.loadRuns() }
        assertFailsWith<IOException> { store.saveRun(sampleRun("run-2", evalPoint = 200)) }

        // 不完全な要約を有効な索引として残さない。次回も再生成を試みて同じ障害で失敗する。
        assertFalse(Files.exists(indexFile(root)), "再生成に失敗した索引を残さない")
        assertFailsWith<IOException> { store.loadIndex() }
    }

    @Test
    fun `壊れた JSON のスキップと IOException の失敗は混ざらない`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))

        Files.writeString(runsDir(root).resolve("broken.json"), "{ \"run\": ")
        // 構文エラーだけならスキップして残りを返す。
        assertEquals(listOf("run-1"), store.loadRuns().map { it.run.id })

        Files.createDirectory(runsDir(root).resolve("io.json"))
        // 読めないファイルが混ざったら、そこから先は失敗にする。
        assertFailsWith<IOException> { store.loadRuns() }
    }

    @Test
    fun `並行に保存しても両方の run が最終 index に残る`() {
        val root = newRoot()
        val store = newStore(root)
        val ids = (1..8).map { "run-$it" }
        val pool = Executors.newFixedThreadPool(ids.size)
        try {
            val start = CountDownLatch(1)
            val futures = ids.mapIndexed { i, id ->
                pool.submit {
                    start.await()
                    store.saveRun(sampleRun(id, evalPoint = (i + 1) * 100))
                }
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val entries = store.loadIndex()
        assertEquals(ids.sorted(), entries.map { it.runId }.sorted())
        assertEquals(ids.sorted(), store.loadRuns().map { it.run.id }.sorted())
    }

    @Test
    fun `カード辞書も1件1ファイルで往復する`() {
        val store = newStore()
        val card = CardMaster(
            cardId = "card-1",
            kind = "support",
            name = "テストカード",
            charId = "char-1",
            rarity = "SSR",
            lessonType = "vo",
            probability = 37.5,
            attr = "vo",
            cost = 10,
            condition = "レッスン3回",
            source = "detail",
            detailOk = true,
        )

        store.saveCard(card)

        assertEquals(card, store.loadCard("card-1"))
        assertEquals(listOf(card), store.loadCards())
    }

    @Test
    fun `日本語を含むカード名が UTF-8 で往復する`() {
        val root = newRoot()
        val store = newStore(root)
        val card = CardMaster(
            cardId = "card-ja-1",
            kind = "support",
            name = "【白線のリズム】篠澤 広",
            charId = "char-1",
            rarity = "SSR",
            lessonType = "vo",
            probability = 37.5,
            attr = "vo",
            cost = 10,
            condition = "レッスン3回・やる気↑",
            source = "detail",
            detailOk = true,
        )

        store.saveCard(card)

        assertEquals(card, store.loadCard("card-ja-1"))
        assertEquals(listOf(card), store.loadCards())

        // 生のファイルが UTF-8 で、非 ASCII をそのまま持っていること。
        // 文字化けや \uXXXX への退避があると、ここで気づける。
        val text = String(Files.readAllBytes(root.resolve("cards/card-ja-1.json")), StandardCharsets.UTF_8)
        assertTrue(text.contains("篠澤 広"), "日本語がそのまま書かれていない: $text")
        assertTrue(text.contains("やる気↑"), "非 ASCII がそのまま書かれていない: $text")
    }

    @Test
    fun `日本語と絵文字を含む run が UTF-8 で往復する`() {
        val root = newRoot()
        val store = newStore(root)
        val base = sampleRun("run-ja-1", evalPoint = 12000)
        val document = base.copy(run = base.run.copy(note = "初見クリア🎉 評価点12,000"))

        store.saveRun(document)

        assertEquals(document, store.loadRun("run-ja-1"))
        assertEquals(listOf("run-ja-1"), store.loadIndex().map { it.runId })

        val text = String(Files.readAllBytes(runsDir(root).resolve("run-ja-1.json")), StandardCharsets.UTF_8)
        assertTrue(text.contains("初見クリア🎉"), "日本語と絵文字がそのまま書かれていない: $text")
    }

    /**
     * 不正な UTF-8 は「壊れた JSON」ではなく I/O 障害として扱う。
     *
     * `String(bytes, UTF_8)`（旧実装）は `c3 28` を U+FFFD と `(` に置き換えるため、下の
     * JSON は置換経路では妥当な構文になり、run が黙って受理される。JDK の `Files.readString`
     * と同じく置換せず [MalformedInputException] にすることを確かめる。
     */
    @Test
    fun `不正な UTF-8 は置換せず MalformedInputException になる`() {
        val root = newRoot()
        val store = newStore(root)
        val file = runsDir(root).resolve("run-bad-utf8.json")
        val head = "{\"run\":{\"id\":\"run-bad-utf8\",\"ranking_id\":\"ranking-1\",\"note\":\""
        val tail = "\"}}"
        Files.write(
            file,
            head.toByteArray(StandardCharsets.UTF_8) +
                byteArrayOf(0xC3.toByte(), 0x28) +
                tail.toByteArray(StandardCharsets.UTF_8),
        )

        // 前提の固定: 置換経路なら U+FFFD + `(` になり、JSON として妥当になってしまう。
        // だから「構文エラーだからスキップ」に吸い込まれると、壊れた文書が一覧に混ざる。
        val lossy = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        assertTrue(lossy.contains('\uFFFD'), "前提: 置換経路では U+FFFD になる: $lossy")
        Json.parseToJsonElement(lossy)

        assertFailsWith<MalformedInputException> { store.loadRun("run-bad-utf8") }
        assertFailsWith<MalformedInputException> { store.loadRuns() }
    }

    /**
     * 孤立サロゲートは保存時に例外にする。`toByteArray(UTF_8)`（旧実装）は `0x3f`（`?`）に
     * 置き換えるため、壊れた文字列が別の値として黙って残る。`Files.writeString` と同じく
     * 置換せず [CharacterCodingException]（[IOException] の派生）にすることを確かめる。
     * 型はこの JDK 17 では `MalformedInputException`（`writeString` は
     * `UnmappableCharacterException`）になる。
     */
    @Test
    fun `孤立サロゲートを含む run は保存できず 0x3f にも置換されない`() {
        val root = newRoot()
        val store = newStore(root)
        val base = sampleRun("run-lone", evalPoint = 100)

        for (broken in listOf("\uD800", "\uDC00")) {
            val document = base.copy(run = base.run.copy(note = "前${broken}後"))

            assertFailsWith<CharacterCodingException> { store.saveRun(document) }

            // 置換経路なら `?` を含む文書が書かれてしまう。失敗時は target も temp も残さない。
            assertFalse(Files.exists(runsDir(root).resolve("run-lone.json")))
            val names = Files.newDirectoryStream(runsDir(root)).use { stream ->
                stream.map { it.fileName.toString() }.toList()
            }
            assertEquals(emptyList(), names, "失敗した保存が temp を残している")
            assertNull(store.loadRun("run-lone"))
        }
    }

    /**
     * PR #11 で決めた区別を固定する。構文・スキーマ違反の1件はスキップして残りを返し、
     * I/O 障害（読めないファイル・不正な UTF-8）はスキップせず失敗する。
     */
    @Test
    fun `構文エラーはスキップし不正な UTF-8 は失敗する`() {
        val root = newRoot()
        val store = newStore(root)
        store.saveRun(sampleRun("run-1", evalPoint = 100))

        // 構文エラーだけなら従来どおりスキップして残りを返す。
        Files.writeString(runsDir(root).resolve("broken.json"), "{ \"run\": ")
        assertEquals(listOf("run-1"), store.loadRuns().map { it.run.id })

        // 不正な UTF-8 は「壊れた JSON」ではなく I/O 障害として扱う。
        val bad = runsDir(root).resolve("bad-utf8.json")
        Files.write(
            bad,
            "{\"run\":{\"id\":\"bad-utf8\",\"ranking_id\":\"ranking-1\",\"note\":\"".toByteArray(StandardCharsets.UTF_8) +
                byteArrayOf(0xC3.toByte(), 0x28) +
                "\"}}".toByteArray(StandardCharsets.UTF_8),
        )
        assertFailsWith<MalformedInputException> { store.loadRuns() }
        assertTrue(Files.exists(indexFile(root)), "読み込みの失敗だけでは索引を消さない")

        // 索引の再生成は不完全な要約を確定しない（既存の IOException 経路に乗る）。
        assertFailsWith<MalformedInputException> {
            store.saveRun(sampleRun("run-2", evalPoint = 200))
        }
        assertFalse(Files.exists(indexFile(root)), "再生成に失敗した索引を残さない")
        assertFailsWith<MalformedInputException> { store.loadIndex() }
    }

    @Test
    fun `ファイル名に使えない id は拒否する`() {
        val store = newStore()
        var rejected = false
        try {
            store.saveRun(sampleRun("../escape", evalPoint = 1))
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
