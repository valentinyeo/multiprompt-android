package dev.multiprompt.companion.ui

import android.app.Application
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.reader.ChatMention
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MentionAvatarTest {
    private val context = RuntimeEnvironment.getApplication()
    private val current = TmuxSession("host", "live-current", 1, 0, 20)
    private val chat = TmuxSession("host", "live-features", 1, 0, 20, title = "Multi Bot Features", workspaceColor = 4)
    private val sessions = listOf(current, chat)

    private fun suggestion(store: SessionReadStore): BotAvatarSource {
        val mention = ChatMention.chats(sessions, current).single()
        return botAvatarSource(mention.session, mention.workspaceColor, store.botLook(mention.hostId, mention.session))
    }

    private fun chatRow(store: SessionReadStore): BotAvatarSource =
        botAvatarSource(chat.name, chat.workspaceColor, store.botLook(chat))

    @Test fun aSetFaceShowsTheSameAvatarInTheMentionMenuAsInTheChatList() {
        val store = SessionReadStore(context)
        store.setBotFace(chat, 19)
        assertEquals(chatRow(store), suggestion(store))
        assertEquals(19, suggestion(store).appearance.face)
        assertEquals(4, suggestion(store).appearance.color)
    }

    @Test fun aCustomFacePictureShowsTheSameAvatarInTheMentionMenuAsInTheChatList() {
        val store = SessionReadStore(context)
        store.setBotFace(chat, 6)
        store.setBotColor(chat, 2)
        store.setBotPicture(chat, "/tmp/faces/multi-bot.png")
        assertEquals(chatRow(store), suggestion(store))
        assertEquals("/tmp/faces/multi-bot.png", suggestion(store).look?.picture)
        assertEquals(2, suggestion(store).appearance.color)
    }

    @Test fun aChatWithNoPickStillMatchesTheChatListFallback() {
        val store = SessionReadStore(context)
        val expected = BotAppearance.forSession(chat.name, chat.workspaceColor)
        assertEquals(expected, suggestion(store).appearance)
        assertEquals(expected, chatRow(store).appearance)
    }
}
