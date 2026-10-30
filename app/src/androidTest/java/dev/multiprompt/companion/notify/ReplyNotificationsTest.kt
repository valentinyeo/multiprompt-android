package dev.multiprompt.companion.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import dev.multiprompt.companion.AppUiState
import dev.multiprompt.companion.account.AccountAuth
import dev.multiprompt.companion.account.AccountHttp
import dev.multiprompt.companion.account.AccountLayout
import dev.multiprompt.companion.account.AccountReply
import dev.multiprompt.companion.account.AccountTab
import dev.multiprompt.companion.account.PendingLayout
import org.json.JSONObject
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReplyNotificationsTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun replies() = manager.activeNotifications.filter { it.notification.group == ReplyNotifier.GROUP_KEY }

    private fun children() = replies().filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(200)
        }
    }

    @Test fun onlyFavoriteRepliesAppearAndUnfavoritingClearsThem() {
        val app = context.applicationContext as MultipromptApplication
        val host = HostProfile("favorite-proof", "Hetzner", "127.0.0.1", username = "valentin", keySecretId = "missing")
        val alpha = TmuxSession(host.id, "alpha", 1, 0, 0, title = "Favorite alpha", paneCommand = "claude")
        val beta = TmuxSession(host.id, "beta", 1, 0, 0, title = "Runner beta", paneCommand = "claude")
        val alphaKey = SessionReadStore.key(host.id, alpha.name)
        val betaKey = SessionReadStore.key(host.id, beta.name)
        val layout = AccountLayout(workspaces = listOf("Other"), tabs = listOf(
            AccountTab("Favorite alpha", "alpha", 0, "Other", favorite = true),
            AccountTab("Runner beta", "beta", 0, "Other"),
        ))
        val authField = app.syncAuth.javaClass.getDeclaredField("auth").apply { isAccessible = true }
        val originalAuth = authField.get(app.syncAuth)
        var model: MainViewModel? = null
        var historyRole = "user"
        var historyText = "/rc"
        fun reply() = instrumentation.runOnMainSync {
            val current = checkNotNull(model)
            val stateField = current.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = stateField.get(current) as MutableStateFlow<AppUiState>
            state.value = state.value.copy(repliedSessionKeys = setOf(alphaKey, betaKey), unreadCounts = mapOf(alphaKey to 1, betaKey to 1))
            current.javaClass.getDeclaredMethod("syncReplyNotifications").apply { isAccessible = true }.invoke(current)
        }
        fun favorite(name: String, enabled: Boolean) = instrumentation.runOnMainSync {
            assertEquals(null, checkNotNull(model).editAccountLayout { it.setFavorite("0:$name", enabled) })
        }
        manager.cancelAll()
        try {
            instrumentation.runOnMainSync {
                app.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE).edit().putLong("last_check_ms", System.currentTimeMillis()).commit()
                val current = MainViewModel(app)
                model = current
                current.notificationHistoryReader = { _, _ ->
                    dev.multiprompt.companion.reader.ChatHistory(sid = "proof", rows = listOf(
                        dev.multiprompt.companion.reader.ChatHistoryProtocol.Row(historyRole, historyText, "reply", "", 0, 1),
                    ))
                }
                val auth = AccountAuth("test://callback", object : AccountHttp {
                    override fun request(method: String, path: String, token: String?, body: JSONObject?): AccountReply = error("No account network expected")
                }, { if (it == AccountAuth.TOKENS) "{\"account_id\":\"notification-proof\"}" else null }, { _, _ -> })
                authField.set(app.syncAuth, auth)
                current.javaClass.getDeclaredField("accountRequestRunning").apply { isAccessible = true }.set(current, true)
                current.javaClass.getDeclaredField("pendingLayout").apply { isAccessible = true }.set(current, PendingLayout(layout))
                current.javaClass.getDeclaredField("replyNotificationsBaselined").apply { isAccessible = true }.set(current, true)
                val stateField = current.javaClass.getDeclaredField("_state").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val state = stateField.get(current) as MutableStateFlow<AppUiState>
                state.value = current.state.value.copy(hosts = listOf(host), sessions = listOf(alpha, beta), signedIn = true,
                    accountLayout = layout, accountHosts = mapOf(0 to host))
            }
            reply()
            instrumentation.waitForIdleSync()
            assertTrue("/rc must not notify", replies().isEmpty())
            historyText = "Please fix checkout"
            reply()
            instrumentation.waitForIdleSync()
            assertTrue("user typing must not notify", replies().isEmpty())
            historyRole = "assistant"
            historyText = "Checkout fixed. Tests passed."
            reply()
            waitFor(10_000) { children().isNotEmpty() }
            assertEquals("Checkout fixed.", text(children().single().notification))
            assertEquals("only the favorite may notify", setOf(alphaKey), ReplyNotifier.activeKeys(context))
            assertEquals("Favorite alpha", title(children().single().notification))
            val shade = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "favorite-only-notifications-shade.png")
            shade.parentFile?.mkdirs()
            instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
            Thread.sleep(2_000)
            shade.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
            instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
            favorite("alpha", false)
            waitFor(10_000) { replies().isEmpty() }
            favorite("beta", true)
            assertTrue("favoriting alone must not replay old replies", replies().isEmpty())
            reply()
            waitFor(10_000) { ReplyNotifier.activeKeys(context) == setOf(betaKey) }
            favorite("beta", false)
            waitFor(10_000) { replies().isEmpty() }
            reply()
            assertTrue("no favorites means no reply notifications", replies().isEmpty())
        } finally {
            instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
            instrumentation.runOnMainSync { model?.viewModelScope?.cancel(); authField.set(app.syncAuth, originalAuth) }
            manager.cancelAll()
        }
    }

    @Test fun twoRepliesInOneSessionShowOneGroupedNotificationAndTapOpensIt() {
        val app = context.applicationContext as MultipromptApplication
        val host = HostProfile(label = "Notify", hostname = "127.0.0.1", username = "nobody")
        val alpha = TmuxSession(host.id, "alpha", 1, 0, 0, title = "Alpha session")
        val beta = TmuxSession(host.id, "beta", 1, 0, 0, title = "Beta session")
        app.hostStore.upsert(host)
        app.sessionCacheStore.save(host.id, listOf(alpha, beta))
        manager.cancelAll()
        var opened: ComponentActivity? = null
        try {
            ReplyNotifier.show(context, host.id, alpha.name, "Alpha session", "first reply from alpha")
            ReplyNotifier.show(context, host.id, alpha.name, "Alpha session", "second reply from alpha")
            ReplyNotifier.show(context, host.id, beta.name, "Beta session", "reply from beta")
            waitFor(10_000) { children().size >= 2 }

            val shown = children()
            assertEquals("one notification per session", 2, shown.size)
            val alphaNote = shown.single { title(it.notification) == "Alpha session" }
            assertEquals("second reply from alpha", text(alphaNote.notification))
            assertTrue("a group summary exists", replies().any { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 })

            val shade = File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "reply-notifications-shade.png",
            )
            shade.parentFile?.mkdirs()
            instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
            Thread.sleep(2_000)
            shade.outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()

            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            alphaNote.notification.contentIntent.send()
            val launched = monitor.waitForActivityWithTimeout(60_000)
            assertNotNull("tapping opens the app", launched)
            val activity = launched as ComponentActivity
            opened = activity
            val model = ViewModelProvider(activity)[MainViewModel::class.java]
            waitFor(30_000) { model.state.value.readerSession?.name == alpha.name }
            waitFor(10_000) { children().none { title(it.notification) == "Alpha session" } }
            ReplyNotifier.cancel(context, SessionReadStore.key(host.id, beta.name))
            waitFor(10_000) { replies().isEmpty() }
        } finally {
            instrumentation.runOnMainSync { opened?.finish() }
            instrumentation.waitForIdleSync()
            manager.cancelAll()
            app.hostStore.delete(host.id)
            app.sessionCacheStore.removeHost(host.id)
        }
    }
}
