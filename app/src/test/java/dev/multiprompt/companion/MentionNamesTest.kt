package dev.multiprompt.companion

import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.AccountTab
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.reader.MentionChat
import org.junit.Assert.assertEquals
import org.junit.Test

class MentionNamesTest {
    private val host = HostProfile("vps", "Hetzner", "vps", username = "valentin", keySecretId = "key")
    private val current = TmuxSession(host.id, "live-this-5511", 1, 0, 0)
    private val design = TmuxSession(host.id, "live-design-5511", 1, 0, 0)
    private val layout = AccountLayout(
        revision = 1,
        workspaces = listOf("Clients"),
        tabs = listOf(
            AccountTab("SF Design Lead", "live-design-5511", 0, "Clients"),
            AccountTab("This chat", "live-this-5511", 0, "Clients"),
        ),
    )
    private val state = AppUiState(
        hosts = listOf(host),
        sessions = listOf(current, design),
        accountLayout = layout,
        accountHosts = mapOf(0 to host),
    )

    @Test fun aLiveChatIsMentionedByTheNameTheChatListShows() {
        assertEquals(
            listOf(
                MentionChat("This chat", "live-this-5511", host.id),
                MentionChat("SF Design Lead", "live-design-5511", host.id),
            ),
            state.mentionChats(current),
        )
    }

    @Test fun everyMentionNameMatchesItsInboxRow() {
        val inbox = state.inboxSessions.map { it.displayName }
        assertEquals(inbox, state.mentionChats(current).map { it.name })
    }
}
