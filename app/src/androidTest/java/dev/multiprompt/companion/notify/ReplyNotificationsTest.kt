package dev.multiprompt.companion.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.multiprompt.companion.MainActivity
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
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

    @Test fun twoRepliesInOneSessionShowOneGroupedNotificationAndTapOpensIt() {
        val app = context.applicationContext as MultipromptApplication
        val host = HostProfile(label = "Notify", hostname = "127.0.0.1", username = "nobody")
        val alpha = TmuxSession(host.id, "alpha", 1, 0, 0, title = "Alpha session")
        val beta = TmuxSession(host.id, "beta", 1, 0, 0, title = "Beta session")
        app.hostStore.upsert(host)
        app.sessionCacheStore.save(host.id, listOf(alpha, beta))
        manager.cancelAll()
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
            val launched = monitor.waitForActivityWithTimeout(20_000)
            assertNotNull("tapping opens the app", launched)
            val activity = launched as ComponentActivity
            val model = ViewModelProvider(activity)[MainViewModel::class.java]
            waitFor(30_000) { model.state.value.readerSession?.name == alpha.name }
            waitFor(10_000) { children().none { title(it.notification) == "Alpha session" } }
            assertEquals("the other session keeps its notification", 1, children().size)
        } finally {
            manager.cancelAll()
            app.hostStore.delete(host.id)
            app.sessionCacheStore.removeHost(host.id)
        }
    }
}
