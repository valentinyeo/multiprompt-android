package dev.multiprompt.companion.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Looper
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.ui.SessionActivityFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FavoriteReplyNotificationsTest {
    private lateinit var fixture: SessionActivityFixture
    private lateinit var manager: NotificationManager
    private val one get() = SessionReadStore.key(fixture.host.id, "one")
    private val two get() = SessionReadStore.key(fixture.host.id, "two")

    @Before fun setUp() {
        fixture = SessionActivityFixture()
        manager = fixture.app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        fixture.state.value = fixture.state.value.copy(sessions = fixture.state.value.sessions.map { it.copy(paneCommand = "claude") })
        fixture.model.notificationHistoryReader = { _, session ->
            val preview = fixture.state.value.sessions.first { it.name == session.name }.preview
            val user = preview.startsWith("❯")
            dev.multiprompt.companion.reader.ChatHistory(sid = "proof", rows = listOf(
                dev.multiprompt.companion.reader.ChatHistoryProtocol.Row(
                    if (user) "user" else "assistant", if (user) preview.removePrefix("❯").trim() else "Checkout fixed. Tests passed.",
                    "reply", "", 0, 1),
            ))
        }
        ReflectionHelpers.setField(fixture.model, "replyNotificationsBaselined", true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun tearDown() {
        manager.cancelAll()
        fixture.close()
    }

    private fun replies() = ReplyNotifier.activeKeys(fixture.app)

    private fun reply(vararg keys: String) {
        fixture.state.value = fixture.state.value.copy(
            repliedSessionKeys = keys.toSet(), unreadCounts = mapOf(one to 1, two to 1),
        )
        ReflectionHelpers.callInstanceMethod<Unit>(fixture.model, "syncReplyNotifications")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun favorite(name: String, enabled: Boolean) {
        assertNull(fixture.model.editAccountLayout { it.setFavorite("0:$name", enabled) })
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun notificationContainsFirstReplySentenceInsteadOfTerminalPrompt() {
        favorite("one", true)
        reply(one)
        assertEquals("Checkout fixed.", manager.activeNotifications.single { it.tag == one }.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun slashCommandDoesNotNotify() {
        favorite("one", true)
        fixture.state.value = fixture.state.value.copy(sessions = listOf(fixture.session.copy(paneCommand = "claude", preview = "❯ /rc")))
        reply(one)
        assertEquals(emptySet<String>(), replies())
    }

    @Test fun typedPromptDoesNotNotify() {
        favorite("one", true)
        fixture.state.value = fixture.state.value.copy(sessions = listOf(fixture.session.copy(paneCommand = "claude", preview = "❯ Please fix the checkout")))
        reply(one)
        assertEquals(emptySet<String>(), replies())
    }

    @Test fun unavailableHistoryNeverFallsBackToTerminalText() {
        favorite("one", true)
        fixture.model.notificationHistoryReader = { _, _ -> null }
        reply(one)
        assertEquals(emptySet<String>(), replies())
        fixture.model.notificationHistoryReader = { _, _ -> error("SSH unavailable") }
        reply(one)
        assertEquals(emptySet<String>(), replies())
    }

    @Test fun noFavoritesMeansNoReplyNotifications() {
        reply(one, two)
        assertEquals(emptySet<String>(), replies())
    }

    @Test fun onlyTheFavoriteNotifiesAndRemovingItsStarClearsIt() {
        favorite("one", true)
        reply(one, two)
        assertEquals(setOf(one), replies())
        favorite("one", false)
        assertEquals(emptySet<String>(), replies())
        favorite("two", true)
        assertEquals(emptySet<String>(), replies())
        reply(one, two)
        assertEquals(setOf(two), replies())
    }

    @Test fun notificationFavoritesUseTheSameHostAndCaseMappingAsTheSessionList() {
        assertNull(fixture.model.editAccountLayout { layout ->
            layout.copy(tabs = layout.tabs.map { if (it.session == "one") it.copy(session = "ONE", favorite = true) else it })
        })
        shadowOf(Looper.getMainLooper()).idle()
        reply(one, two)
        assertEquals(setOf(one), replies())
    }

    @Test fun removingFavoritesLeavesAppUpdateNotificationsAlone() {
        manager.createNotificationChannel(NotificationChannel("app_updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(99, Notification.Builder(fixture.app, "app_updates").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("App update ready").build())
        favorite("one", true)
        reply(one)
        favorite("one", false)
        assertEquals(emptySet<String>(), replies())
        assertEquals(listOf(99), manager.activeNotifications.map { it.id })
    }
}
