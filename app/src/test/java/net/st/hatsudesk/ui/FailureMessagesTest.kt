package net.st.hatsudesk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.NoSuchFileException

/**
 * 画面に出す文言に英語（例外型名・OS のメッセージ）が混ざらないこと。
 * 例外の中身はログ側にだけ残す。
 */
class FailureMessagesTest {

    private fun assertJapaneseOnly(message: String) {
        assertFalse("英語が混ざっている: $message", Regex("[A-Za-z]").containsMatchIn(message))
    }

    @Test
    fun ioFailureShowsJapaneseOnly() {
        val message = userMessageFor(IOException("Permission denied"))

        assertJapaneseOnly(message)
        assertTrue(message, message.contains("保存領域"))
    }

    @Test
    fun unexpectedFailureShowsJapaneseOnly() {
        val message = userMessageFor(IllegalStateException("closed"))

        assertJapaneseOnly(message)
        assertTrue(message, message.contains("予期しない失敗"))
    }

    @Test
    fun platformErrorWithEnglishMessageShowsJapaneseOnly() {
        val message = userMessageFor(NoSuchMethodError("no method readString in java.nio.file.Files"))

        assertJapaneseOnly(message)
        assertTrue(message, message.contains("予期しない失敗"))
    }

    @Test
    fun securityFailureShowsJapaneseOnlyAndDoesNotLeakDetail() {
        val message = userMessageFor(SecurityException("EACCES: /data/user/0/net.st.hatsudesk"))

        assertJapaneseOnly(message)
        assertTrue(message, message.contains("保存領域"))
    }
}
