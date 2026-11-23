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
        assertEquals(514581, bundle.size)
        assertEquals("40b375531febfa1e1b7f9279c85102279bf0e94284aae6b0848bd05e5d5eb645", hash)
    }
}
