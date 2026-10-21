package dev.multiprompt.companion.account

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.data.CrashReportStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashReportUploadLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val secrets = MemoryDeviceSecrets()
    private val initialSessionRead = CountDownLatch(1)
    private val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
    private lateinit var app: MultipromptApplication
    private lateinit var model: MainViewModel
    @Volatile private var crashStatus = 201
    @Volatile private var layoutStatus = 200
    private val trace = "java.lang.IllegalStateException: private message\n" +
        "\tat dev.multiprompt.companion.MainViewModel.load(MainViewModel.kt:42)"

    @After fun close() {
        compose.runOnIdle { models.clear() }
        if (::app.isInitialized) app.deepgramDictation.close()
    }

    private fun signIn() = synchronized(secrets) {
        secrets.put(AccountAuth.TOKENS, JSONObject().put("account_id", "test-account").put("device_id", "phone")
            .put("access_token", "a".repeat(43)).put("expires_at", Long.MAX_VALUE).toString().toByteArray())
    }

    private fun setup(signedIn: Boolean) {
        compose.runOnIdle {
            app = MultipromptApplication()
            ReflectionHelpers.callInstanceMethod<Unit>(app, "attach",
                ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()))
            for (name in listOf("hosts", "account_layout", "encrypted_secrets", "crash_report")) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
            app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                .putLong("last_check_ms", System.currentTimeMillis()).commit()
            app.getSharedPreferences("crash_report", Context.MODE_PRIVATE).edit()
                .putLong("captured_at", 1792368000L).putString("thread", "main")
                .putString("summary", "private message").putString("stack_trace", trace).commit()
            app.crashReportStore.dismiss()
            if (signedIn) signIn()
            val http = AccountHttp { method, path, token, body ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                assertEquals("a".repeat(43), token)
                when (path) {
                    "/v1/crash-reports" -> {
                        assertEquals("POST", method)
                        val status = crashStatus
                        bodies.add(JSONObject(body.toString()))
                        AccountReply(status, JSONObject().put("stored", status == 201))
                    }
                    "/v1/layout" -> AccountReply(layoutStatus,
                        JSONObject().put("revision", 1).put("layout", AccountLayout(1).json()))
                    "/v1/device-key" -> AccountReply(404, JSONObject())
                    else -> error("Unexpected request $method $path")
                }
            }
            val auth = AccountAuth("test://callback", http, { id ->
                val value = synchronized(secrets) { secrets.get(id)?.toString(Charsets.UTF_8) }
                if (id == AccountAuth.TOKENS && Looper.myLooper() != Looper.getMainLooper()) {
                    initialSessionRead.countDown()
                }
                value
            }, { id, value -> synchronized(secrets) {
                if (value == null) secrets.remove(id) else secrets.put(id, value.toByteArray())
            } })
            ReflectionHelpers.setField(app, "accountHttp\$delegate", lazyOf(http))
            ReflectionHelpers.setField(app.syncAuth, "auth", auth)
            ReflectionHelpers.setField(app, "deviceKeyStore\$delegate", lazyOf(DeviceKeyStore(secrets)))
            model = MainViewModel(app).also { models.put("main", it) }
        }
        assertTrue(initialSessionRead.await(15, TimeUnit.SECONDS))
    }

    private fun resumeUntilUploaded() {
        compose.runOnIdle { model.onResume() }
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            app.crashReportStore.loadPending().isEmpty() && !model.state.value.accountSyncing &&
                !ReflectionHelpers.getField<Boolean>(model, "deviceKeyRefreshRunning")
        }
    }

    @Test fun dismissedLegacyReportUploadsAfterSessionBecomesAvailableOnForeground() {
        layoutStatus = 503
        setup(signedIn = false)
        val legacy = CrashReportStore(app).loadPending().single()
        assertTrue(bodies.isEmpty())
        assertNull(app.crashReportStore.loadForDisplay())
        assertFalse(app.getSharedPreferences("crash_report", Context.MODE_PRIVATE).contains("id"))
        signIn()
        resumeUntilUploaded()
        val sent = bodies.single()
        assertEquals(legacy.id, sent.getString("id"))
        assertEquals(1792368000L, sent.getLong("captured_at"))
        assertEquals("unknown", sent.getString("app_version"))
        assertEquals("unknown", sent.getString("android_version"))
        assertEquals("java.lang.IllegalStateException\n" +
            "at dev.multiprompt.companion.MainViewModel.load(MainViewModel.kt:42)", sent.getString("stack_trace"))
        assertFalse(sent.toString().contains("private message"))
        assertNull(CrashReportStore(app).load())
        assertTrue(model.state.value.signedIn)
        assertNotNull(model.state.value.accountError)
    }

    @Test fun foregroundRetriesSameLegacyReportAfterServerWasUnavailable() {
        crashStatus = 404
        setup(signedIn = true)
        compose.waitUntil(15_000) { bodies.size == 1 }
        val legacy = CrashReportStore(app).loadPending().single()
        compose.runOnIdle { model.onPause() }
        crashStatus = 201
        resumeUntilUploaded()
        assertEquals(2, bodies.size)
        assertEquals(bodies[0].toString(), bodies[1].toString())
        assertEquals(legacy.id, bodies[1].getString("id"))
        assertNull(CrashReportStore(app).load())
        compose.runOnIdle { model.onPause(); model.onResume() }
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.state.value.accountSyncing &&
                !ReflectionHelpers.getField<Boolean>(model, "deviceKeyRefreshRunning")
        }
        assertEquals(2, bodies.size)
    }
}
