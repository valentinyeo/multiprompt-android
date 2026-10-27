package dev.multiprompt.companion.account

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.AppSection
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.data.HostStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ui.HostMappingDialog
import dev.multiprompt.companion.ui.MultipromptTheme
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

class AccountHostNamesTest {
    @Test fun accountNamesReplaceNumericLabelsAndLaterRenamesReachAlreadyMappedProfiles() {
        val profiles = listOf(
            HostProfile(label = "1", hostname = "46.224.218.39", username = "valentin"),
            HostProfile(label = "2", hostname = "80.190.82.74", username = "valentin"),
        )
        val remote = listOf(
            AccountHost(0, "VPS Hetzner", "46.224.218.39", 22, "valentin"),
            AccountHost(1, "VPS Contabo", "80.190.82.74", 22, "valentin"),
        )
        val merged = AccountHosts.merge(remote, profiles, emptyMap())
        assertEquals(profiles.mapIndexed { slot, profile -> profile.copy(label = remote[slot].name) }, merged.profiles)
        val mapping = AccountHostMap.resolve(merged.profiles, merged.overrides)
        assertEquals(profiles.map { it.id }, (0..1).map { mapping.getValue(it).id })
        assertEquals(listOf("1", "2"), profiles.map { it.label })

        val renamed = remote.map { if (it.slot == 0) it.copy(name = "VPS Hetzner Main") else it }
        val again = AccountHosts.merge(renamed, merged.profiles, merged.overrides)
        assertEquals(profiles.mapIndexed { slot, profile -> profile.copy(label = renamed[slot].name) }, again.profiles)
        assertEquals(merged.overrides, again.overrides)
        assertEquals(again, AccountHosts.merge(renamed, again.profiles, again.overrides))
    }

    @Test fun explicitMappingsTakeAccountNamesWithoutReplacingLocalConnectionOrTrust() {
        val profile = HostProfile(label = "1", hostname = "local.example", port = 2222, username = "valentin",
            keySecretId = "local-key", passphraseSecretId = "local-passphrase", hostKeyType = "ssh-rsa",
            hostKeyFingerprint = "SHA256:local", hostKeys = listOf("ssh-rsa bG9jYWw="),
            dismissedHostKeyFingerprint = "ssh-rsa SHA256:dismissed")
        val remote = AccountHost(0, "VPS Hetzner", "46.224.218.39", 22, "valentin")
        val overrides = mapOf(0 to profile.id)
        val merged = AccountHosts.merge(listOf(remote), listOf(profile), overrides)
        assertEquals(listOf(profile.copy(label = remote.name)), merged.profiles)
        assertEquals(overrides, merged.overrides)
        val renamed = remote.copy(name = "VPS Hetzner Main")
        assertEquals(listOf(profile.copy(label = renamed.name)),
            AccountHosts.merge(listOf(renamed), merged.profiles, merged.overrides).profiles)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountHostNamesAppTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var app: MultipromptApplication
    private lateinit var model: MainViewModel
    private lateinit var client: OkHttpClient
    private val profiles = listOf(
        HostProfile(label = "1", hostname = "46.224.218.39", username = "valentin"),
        HostProfile(label = "2", hostname = "80.190.82.74", username = "valentin"),
    )
    private var remote = AccountLayout(1, hosts = listOf(
        AccountHost(0, "VPS Hetzner", "46.224.218.39", 22, "valentin"),
        AccountHost(1, "VPS Contabo", "80.190.82.74", 22, "valentin"),
    ))

    @After fun close() {
        compose.runOnIdle { models.clear() }
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        if (::app.isInitialized) app.deepgramDictation.close()
    }

    private fun setup() {
        compose.runOnIdle {
            app = MultipromptApplication()
            ReflectionHelpers.callInstanceMethod<Unit>(app, "attach",
                ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()))
            for (name in listOf("hosts", "account_layout", "encrypted_secrets")) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
            app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putLong("last_check_ms", System.currentTimeMillis()).commit()
            profiles.forEach(app.hostStore::upsert)
            model = MainViewModel(app).also { models.put("main", it) }
            assertEquals(profiles, model.state.value.hosts)
            client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("GET", request.method)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("X-Layout-Features", "favorite,used,visits,input,settings,hosts")
                    .body(JSONObject().put("revision", remote.revision).put("layout", remote.json()).toString().toResponseBody()).build()
            }.build()
            val http = AccountHttpClient(client)
            val tokens = JSONObject().put("account_id", "test-account").put("device_id", "test-device")
                .put("access_token", "a".repeat(43)).put("expires_at", Long.MAX_VALUE).toString()
            val auth = AccountAuth("test://callback", http, { if (it == AccountAuth.TOKENS) tokens else null }, { _, _ -> })
            ReflectionHelpers.setField(model.syncAuth, "auth", auth)
            ReflectionHelpers.setField(model.syncAuth, "http", http)
        }
    }

    private fun sync() {
        compose.runOnIdle { model.syncAccount() }
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.state.value.accountLayout != null && !model.state.value.accountSyncing
        }
        compose.runOnIdle {
            assertTrue(model.state.value.signedIn)
            assertNull(model.state.value.accountError)
            assertFalse(model.state.value.accountPending)
        }
    }

    @Test fun accountPullRenamesSavedNumericHostsAndHostsScreenOnEveryPull() {
        setup()
        sync()
        compose.runOnIdle {
            val expected = profiles.mapIndexed { slot, profile -> profile.copy(label = remote.hosts!![slot].name) }.associateBy { it.id }
            assertEquals(expected, HostStore(app).load().associateBy { it.id })
            assertEquals(expected, model.state.value.hosts.associateBy { it.id })
            assertEquals(expected, model.state.value.accountHosts.values.associateBy { it.id })
            model.select(AppSection.SESSIONS)
        }
        compose.setContent { MultipromptTheme { HostMappingDialog(model, onDismiss = {}) } }
        compose.onNodeWithText("0 · VPS Hetzner: VPS Hetzner (valentin)").assertIsDisplayed()
        compose.onNodeWithText("1 · VPS Contabo: VPS Contabo (valentin)").assertIsDisplayed()
        compose.onNodeWithText("1").assertDoesNotExist()
        compose.onNodeWithText("2").assertDoesNotExist()

        compose.runOnIdle {
            remote = remote.copy(revision = remote.revision + 1,
                hosts = remote.hosts!!.map { if (it.slot == 0) it.copy(name = "VPS Hetzner Main") else it })
        }
        sync()
        compose.runOnIdle {
            val expected = profiles.mapIndexed { slot, profile -> profile.copy(label = remote.hosts!![slot].name) }.associateBy { it.id }
            assertEquals(expected, HostStore(app).load().associateBy { it.id })
            assertEquals(expected, model.state.value.hosts.associateBy { it.id })
            assertEquals(expected, model.state.value.accountHosts.values.associateBy { it.id })
        }
        compose.onNodeWithText("0 · VPS Hetzner Main: VPS Hetzner Main (valentin)").assertIsDisplayed()
        compose.onNodeWithText("0 · VPS Hetzner: VPS Hetzner (valentin)").assertDoesNotExist()
        compose.onNodeWithText("1 · VPS Contabo: VPS Contabo (valentin)").assertIsDisplayed()
        compose.onNodeWithText("1").assertDoesNotExist()
        compose.onNodeWithText("2").assertDoesNotExist()
    }
}
