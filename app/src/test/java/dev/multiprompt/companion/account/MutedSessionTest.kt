package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutedSessionTest {
    private val host = HostProfile("v", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val mapping = mapOf(0 to host)
    private val session = TmuxSession(host.id, "one", 1, 0, 0)
    private val tab = AccountTab("One", "one", 0, "Other")
    private val layout = AccountLayout(1, listOf("Other"), listOf(tab))

    @Test fun mutedChatIsHiddenFromTheInboxButFoundBySearchAndFavorites() {
        val muted = layout.setMuted(tab.identity, true)
        assertTrue(isHiddenAsMuted(session, muted, mapping, null))
        assertTrue(isHiddenAsMuted(session, muted, mapping, "Other"))
        assertFalse(isHiddenAsMuted(session, muted, mapping, FAVORITES_SPLIT_ID))
        assertFalse(isHiddenAsMuted(session, muted, mapping, null, searching = true))
        assertFalse(isHiddenAsMuted(session, muted, mapping, "Other", searching = true))
    }

    @Test fun unmutingBringsTheChatBackToTheInbox() {
        val muted = layout.setMuted(tab.identity, true)
        val unmuted = muted.setMuted(tab.identity, false)
        assertTrue(isMutedSession(session, muted, mapping))
        assertFalse(isMutedSession(session, unmuted, mapping))
        assertFalse(isHiddenAsMuted(session, unmuted, mapping, null))
        assertEquals(false, unmuted.tabs[0].muted)
    }
}
