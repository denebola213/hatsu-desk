package net.st.hatsudesk.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR 0007 の境界を機械的に検査する。
 *
 * `:core` は JVM モジュールなので、`android.*` を import した時点で思想ではなく
 * 依存関係の問題になる。テストで先に落として、レビューで見落とさないようにする。
 */
class NoAndroidDependencyTest {

    private val importRegex = Regex("""(?m)^\s*import\s+android\.""")

    @Test
    fun `core のソースに android の import が無い`() {
        val srcRoot = System.getProperty("hatsudesk.core.src")
            ?.let { Path.of(it) }
            ?: error("hatsudesk.core.src が設定されていない（core/build.gradle.kts の test 設定を確認）")

        assertTrue(Files.isDirectory(srcRoot), "src が見つからない: $srcRoot")

        val kotlinFiles = Files.walk(srcRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
        }
        assertTrue(kotlinFiles.isNotEmpty(), "検査対象の .kt が1つも無い: $srcRoot")

        val offenders = kotlinFiles.filter { file ->
            importRegex.containsMatchIn(file.readText())
        }

        assertTrue(
            offenders.isEmpty(),
            "android.* を import しているファイルがある: " +
                offenders.joinToString { it.toString() },
        )
    }
}
