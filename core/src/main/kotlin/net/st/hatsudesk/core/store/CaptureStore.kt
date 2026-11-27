package net.st.hatsudesk.core.store

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import kotlin.concurrent.withLock

/** `captures/` の1ファイル。 */
data class CaptureFile(val name: String, val sizeBytes: Long)

/**
 * 投影したフレームの PNG を `captures/<name>.png` に置く（設計 §4.1 の暫定保存）。
 *
 * `:app` は Bitmap を PNG のバイト列にするところまでを持ち、ファイルの書き方はここに閉じる。
 * `android.*` は使わない（ADR 0007）ので JVM のテストで回せる。
 *
 * 書き込みは [DocumentStore] と同じ方針にする。
 *
 * - 同一ディレクトリの temp に書いて rename で置き換える（読み手が中途半端な PNG を見ない）
 * - 書き込みは [StoreWriteLock] で直列化する（run の保存と同じロック）
 *
 * 名前はファイル名として安全な PNG だけを受け付ける。呼出側（`:app`）が作った名前を
 * そのまま結合すると、`../` などで `filesDir` の外に書けてしまうため。
 */
class CaptureStore(root: Path) {

    private val dir: Path = root.toAbsolutePath().normalize().resolve("captures")
    private val lock = StoreWriteLock.lock

    init {
        Files.createDirectories(dir)
    }

    /**
     * [bytes] を `captures/<name>` に書く。同じ名前があれば置き換える。
     * 書けたファイルの名前とサイズを返す。
     */
    fun save(name: String, bytes: ByteArray): CaptureFile {
        val safe = requirePngName(name)
        require(bytes.isNotEmpty()) { "空の画像は保存しない: $safe" }
        lock.withLock {
            writeAtomically(dir.resolve(safe), bytes)
        }
        return CaptureFile(name = safe, sizeBytes = bytes.size.toLong())
    }

    /** `captures` 直下の `.png` を名前順に返す。ディレクトリが無ければ空。 */
    fun list(): List<CaptureFile> = lock.withLock {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.newDirectoryStream(dir, "*.png").use { stream ->
            stream
                .map { CaptureFile(name = it.fileName.toString(), sizeBytes = Files.size(it)) }
                .sortedBy { it.name }
        }
    }

    fun count(): Int = list().size

    /**
     * ファイル名として安全で、PNG であることだけを許す。
     * `CaptureStore` の外で作られた名前を検証せずに結合しない。
     */
    private fun requirePngName(name: String): String {
        require(PNG_NAME.matches(name)) { "captures に置けない名前: $name" }
        return name
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(temp, bytes, CREATE, TRUNCATE_EXISTING, WRITE)
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                // temp と target は同一ディレクトリなので通常は起きないが、環境次第で落とさない。
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        /** 先頭は英数字。以降は英数字と `._-`。拡張子は `.png` 固定。 */
        val PNG_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.png""")
    }
}
