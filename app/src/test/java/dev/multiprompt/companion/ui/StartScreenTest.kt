package dev.multiprompt.companion.ui

import dev.multiprompt.companion.AppSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartScreenTest {
    @Test
    fun appOpensOnInbox() {
        assertTrue(StartScreen.INBOX_ON_LAUNCH)
    }

    @Test
    fun backFromHostsAndUpdateReachesWorkspaces() {
        assertFalse(StartScreen.inboxAfterLeaving(AppSection.HOSTS))
        assertFalse(StartScreen.inboxAfterLeaving(AppSection.UPDATE))
        assertTrue(StartScreen.inboxAfterLeaving(AppSection.SESSIONS))
    }
}
