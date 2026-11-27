package net.st.hatsudesk.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.Comparator
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0011 決定3 の denylist 検査。
 *
 * `:core/src/main` に `readString` / `writeString` が再混入したら `:core:test` を落とす。
 * これらは JDK にはあるが Android の API 面（android-36 の `api-versions.xml`）に無く、
 * `minSdk 26` の端末では `NoSuchMethodError` になる。
 *
 * `Files.readString(...)` のようなレシーバ付きの呼び出しだけでなく、static import した
 * `readString(...)` も同じ規則で拾うため、メソッド名そのものを見る。文字列リテラルや
 * コメントにも反応する副作用は ADR 0011 が許容している（狭いが確実な検査を優先する）。
 */
class BannedApiTest {

    /** 禁止するメソッド名。ADR 0011 決定2・3 と同じ集合。 */
    private val bannedNames = listOf("readString", "writeString")

    private val bannedCall = Regex("""\b(?:${bannedNames.joinToString("|")})\b""")

    /**
     * [files] を1行ずつ走査し、禁止名を含む行を `path:line: 行` の形で返す。
     * 空なら違反なし。
     */
    private fun scan(files: List<Path>): List<String> =
        files.flatMap { file ->
            file.readText().lineSequence().mapIndexedNotNull { index, line ->
                if (bannedCall.containsMatchIn(line)) "${file}:${index + 1}: ${line.trim()}" else null
            }.toList()
        }

    private fun kotlinFiles(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
        }

    private fun writeSource(dir: Path, name: String, body: String): Path {
        val file = dir.resolve(name)
        Files.write(
            file,
            body.toByteArray(StandardCharsets.UTF_8),
            CREATE,
            TRUNCATE_EXISTING,
            WRITE,
        )
        return file
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("hatsudesk-banned-api-")
        try {
            block(dir)
        } finally {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `src main に readString writeString が無い`() {
        val src = System.getProperty("hatsudesk.core.src")
            ?.let { Path.of(it) }
            ?: error("hatsudesk.core.src が設定されていない（core/build.gradle.kts の test 設定を確認）")
        val srcMain = src.resolve("main")
        assertTrue(Files.isDirectory(srcMain), "src/main が見つからない: $srcMain")

        val files = kotlinFiles(srcMain)
        // 空回りしていないこと。対象が無ければ「違反0件」は何も意味しない。
        assertTrue(files.isNotEmpty(), "検査対象の .kt が1つも無い: $srcMain")

        val violations = scan(files)
        assertTrue(
            violations.isEmpty(),
            "Android API 26 に無いメソッドを使っている（ADR 0011）:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `検査は禁止 API を含むソースを検出する`() = withTempDir { dir ->
        // static import とレシーバ付きの両方を1つのダミーに入れる。どちらの書き方でも落ちること。
        val positive = writeSource(
            dir,
            "Positive.kt",
            """
            package dummy

            import java.nio.file.Files.readString

            fun viaStaticImport(path: java.nio.file.Path): String = readString(path)

            fun viaReceiver(path: java.nio.file.Path): String =
                java.nio.file.Files.readString(path)

            fun write(path: java.nio.file.Path, body: String): java.nio.file.Path =
                java.nio.file.Files.writeString(path, body)
            """.trimIndent(),
        )

        val violations = scan(listOf(positive))

        // static import・static import 経由の呼び出し・レシーバ付き・writeString の4行が拾われる。
        assertEquals(4, violations.size, "検出数が合わない:\n" + violations.joinToString("\n"))
        assertTrue(violations.all { it.startsWith("$positive:") }, "違反の場所が違う: $violations")
    }

    @Test
    fun `検査は許可した代替 API を検出しない`() = withTempDir { dir ->
        val negative = writeSource(
            dir,
            "Negative.kt",
            """
            package dummy

            import java.nio.charset.StandardCharsets
            import java.nio.file.Files
            import java.nio.file.Path
            import java.nio.file.StandardOpenOption.CREATE
            import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            import java.nio.file.StandardOpenOption.WRITE

            fun readAll(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

            fun writeAll(path: Path, body: String): Path =
                Files.write(
                    path,
                    body.toByteArray(StandardCharsets.UTF_8),
                    CREATE,
                    TRUNCATE_EXISTING,
                    WRITE,
                )
            """.trimIndent(),
        )

        val violations = scan(listOf(negative))

        assertTrue(violations.isEmpty(), "許可した API を誤検出した:\n" + violations.joinToString("\n"))
    }
}
