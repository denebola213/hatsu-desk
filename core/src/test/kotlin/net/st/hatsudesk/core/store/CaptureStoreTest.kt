package net.st.hatsudesk.core.store

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `captures/<timestamp>.png` の保存（Issue #18）。`:core` の書き込み直列化に乗せつつ、
 * 一時ファイルを残さず、名前の検証で `filesDir` の外に出ないことを見る。
 */
class CaptureStoreTest {

    private lateinit var root: Path
    private lateinit var store: CaptureStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("hatsudesk-captures-")
        store = CaptureStore(root)
    }

    @AfterTest
    fun tearDown() {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    @Test
    fun `保存すると captures の下に PNG ができる`() {
        val written = store.save("20261127-120000-000.png", bytes("png"))

        assertEquals("20261127-120000-000.png", written.name)
        assertEquals(3L, written.sizeBytes)
        val file = root.resolve("captures").resolve("20261127-120000-000.png")
        assertTrue(Files.isRegularFile(file), "保存先が無い: $file")
        assertContentEquals(bytes("png"), Files.readAllBytes(file))
    }

    @Test
    fun `同じ名前で保存すると置き換わる`() {
        store.save("same.png", bytes("first"))
        store.save("same.png", bytes("second"))

        assertEquals(1, store.count())
        assertContentEquals(bytes("second"), Files.readAllBytes(root.resolve("captures").resolve("same.png")))
    }

    @Test
    fun `ファイル名として安全でない名前を拒否する`() {
        val bad = listOf(
            "../escape.png",
            "a/b.png",
            "..png",
            "",
            "capture.jpg",
            ".hidden.png",
            "name with space.png",
        )
        bad.forEach { name ->
            assertFailsWith<IllegalArgumentException>("受理してはいけない: $name") {
                store.save(name, bytes("x"))
            }
        }
        assertTrue(store.list().isEmpty(), "拒否した名前でファイルができている: ${store.list()}")
    }

    @Test
    fun `空の画像を拒否する`() {
        assertFailsWith<IllegalArgumentException> { store.save("empty.png", ByteArray(0)) }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `list は名前順にサイズ付きで返す`() {
        store.save("b.png", bytes("22"))
        store.save("a.png", bytes("1"))

        val files = store.list()

        assertEquals(listOf("a.png", "b.png"), files.map { it.name })
        assertEquals(listOf(1L, 2L), files.map { it.sizeBytes })
    }

    @Test
    fun `並行に保存しても全件が正しい内容で残り、一時ファイルを残さない`() {
        val threads = 8
        val perThread = 10
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            Thread {
                start.await()
                repeat(perThread) { n ->
                    val name = "capture-$t-$n.png"
                    store.save(name, bytes("body-$t-$n"))
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "保存が終わらない")

        val expected = threads * perThread
        val files = store.list()
        assertEquals(expected, files.size)

        // 中身が入れ替わっていないこと（temp からの rename が原子的であること）。
        repeat(threads) { t ->
            repeat(perThread) { n ->
                val name = "capture-$t-$n.png"
                assertContentEquals(bytes("body-$t-$n"), Files.readAllBytes(root.resolve("captures").resolve(name)))
            }
        }

        // `.tmp` が残っていないこと。
        val leftovers = Files.newDirectoryStream(root.resolve("captures")).use { stream ->
            stream.map { it.fileName.toString() }.filter { !it.endsWith(".png") }
        }
        assertTrue(leftovers.isEmpty(), "一時ファイルが残っている: $leftovers")
    }
}
