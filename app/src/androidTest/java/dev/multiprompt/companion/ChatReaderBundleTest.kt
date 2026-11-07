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
        assertEquals(415268, bundle.size)
        assertEquals("e2ab0aec5dfb38fcf23c26058813a17b790833a8c84abe0d101f31b55218954d", hash)
    }
}
