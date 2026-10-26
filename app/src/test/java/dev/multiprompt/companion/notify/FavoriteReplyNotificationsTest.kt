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
