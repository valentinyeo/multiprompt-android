package dev.multiprompt.companion

import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReaderBundleTest {
    @Test fun bundledReaderMatchesPublishedDesktopBytes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        val versionLine = "MULTIBOT_APP_VERSION = 'android-${BuildConfig.VERSION_NAME}'\n".toByteArray(Charsets.UTF_8)
        val full = app.chatHistoryScript
        val at = String(full, Charsets.ISO_8859_1).indexOf(String(versionLine, Charsets.ISO_8859_1))
        assertTrue(at > 0)
        val bundle = full.copyOfRange(0, at) + full.copyOfRange(at + versionLine.size, full.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(bundle)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val pin = app.assets.open("chat_history_reader.sha").use { it.readBytes().toString(Charsets.UTF_8) }.trim().lines()
        assertEquals("chat_history_reader.sha holds the desktop revision and, on its second line, the bundle fingerprint", 2L, pin.size.toLong())
        val fingerprint = pin[1].split(" ")
        assertEquals("chat_history_reader.sha line two holds '<length> <sha256>'", 2L, fingerprint.size.toLong())
        assertEquals("The bundled reader must have the length tools/sync-chat-reader.sh recorded for this desktop revision", fingerprint[0].toLong(), bundle.size.toLong())
        assertEquals("The bundled reader must have the SHA-256 tools/sync-chat-reader.sh recorded for this desktop revision", fingerprint[1], hash)
    }
}
