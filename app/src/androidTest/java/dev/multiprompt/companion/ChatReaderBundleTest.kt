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
        assertEquals(222844, bundle.size)
        assertEquals("c93f6148e0437b2c9588ce0dc49277202ab25bf8a201db4f63bfe107a5e1dc06", hash)
    }
}
