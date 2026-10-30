package dev.multiprompt.companion

import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatReaderBundleTest {
    @Test fun bundledReaderMatchesPublishedDesktopBytes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MultipromptApplication
        val bundle = app.chatHistoryScript
        val hash = MessageDigest.getInstance("SHA-256").digest(bundle)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(224961, bundle.size)
        assertEquals("999ef191b364af22994311f4d8b168e22c67a7103585a0c22e0e9c1cc7a9c316", hash)
    }
}
