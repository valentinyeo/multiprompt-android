package dev.multiprompt.companion.account

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import dev.multiprompt.companion.AppSection
import dev.multiprompt.companion.MainViewModel
import dev.multiprompt.companion.MultipromptApplication
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ui.MultipromptApp
import dev.multiprompt.companion.ui.MultipromptTheme
import dev.multiprompt.companion.ui.PendingDevice
import dev.multiprompt.companion.ui.sshKeyFingerprint
import org.json.JSONArray
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
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceKeyWiringTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var app: MultipromptApplication
    private lateinit var model: MainViewModel
    private lateinit var auth: AccountAuth
    private val secrets = MemoryDeviceSecrets()
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val published = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val commands = Collections.synchronizedList(mutableListOf<Pair<HostProfile, String>>())
    private val releaseList = CountDownLatch(1)
    private val releaseHost = CountDownLatch(1)
    private val other = DeviceKeyEntry("laptop", "My laptop", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICAYGZ8wT28oTGncjtNzBoZDBnaWkiurSTzige2NVMZg", DeviceKeyStatus.PENDING)
    private val own = other.copy(deviceId = "phone", name = "This phone")
    private val profiles = listOf(
        HostProfile(id = "uk", label = "Contabo UK", hostname = "uk.invalid", username = "test"),
        HostProfile(id = "de", label = "Hetzner", hostname = "de.invalid", username = "test"),
    )
    @Volatile private var entries = listOf(own, other)
    @Volatile private var unavailablePath: String? = null
    @Volatile private var offlinePath: String? = null
    @Volatile private var failingHost: String? = null
    @Volatile private var blockList = false
    @Volatile private var blockHost = false

    @After fun close() {
        releaseList.countDown()
        releaseHost.countDown()
        compose.runOnIdle { models.clear() }
        if (::app.isInitialized) app.deepgramDictation.close()
    }

    private fun setup(signedIn: Boolean = true) {
        compose.runOnIdle {
            app = MultipromptApplication()
            ReflectionHelpers.callInstanceMethod<Unit>(app, "attach",
                ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()))
            for (name in listOf("hosts", "account_layout", "encrypted_secrets", "crash_report")) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
            app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                .putLong("last_check_ms", System.currentTimeMillis()).commit()
            val tokens = JSONObject().put("account_id", "test-account").put("device_id", "phone")
                .put("access_token", "a".repeat(43)).put("expires_at", Long.MAX_VALUE).toString()
            if (signedIn) secrets.put(AccountAuth.TOKENS, tokens.toByteArray())
            val http = AccountHttp { method, path, token, body ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                requests.add("$method $path")
                if (path != "/oauth/token") assertEquals("a".repeat(43), token)
                if (path == offlinePath) throw IOException("offline")
                if (path == unavailablePath) AccountReply(404, JSONObject()) else when (path) {
                    "/oauth/token" -> AccountReply(200, JSONObject(tokens).put("refresh_token", "r".repeat(43))
                        .put("token_type", "Bearer").put("expires_in", 3600))
                    "/v1/layout" -> AccountReply(200, JSONObject().put("revision", 1).put("layout", AccountLayout(1).json()))
                    "/v1/device-key" -> {
                        assertEquals("PUT", method)
                        published.add(JSONObject(body.toString()))
                        AccountReply(200, JSONObject().put("status", "pending"))
                    }
                    "/v1/device-keys" -> {
                        if (blockList) assertTrue(releaseList.await(15, TimeUnit.SECONDS))
                        AccountReply(200, JSONObject().put("device_id", "phone").put("keys", JSONArray(entries.map {
                            JSONObject().put("device_id", it.deviceId).put("name", it.name)
                                .put("public_key", it.publicKey).put("status", it.status.name.lowercase())
                        })))
                    }
                    "/v1/device-keys/laptop/approve", "/v1/device-keys/laptop/deny" -> {
                        assertEquals("POST", method)
                        assertEquals("{}", body.toString())
                        val approved = path.endsWith("/approve")
                        if (approved) {
                            assertEquals(profiles, commands.map { it.first })
                            assertTrue(commands.all { it.second == DeviceKeys.addCommand(other.publicKey) })
                        }
                        entries = entries.map { if (it.deviceId == other.deviceId) it.copy(
                            status = if (approved) DeviceKeyStatus.APPROVED else DeviceKeyStatus.DENIED) else it }
                        AccountReply(200, JSONObject().put("status", if (approved) "approved" else "denied"))
                    }
                    else -> error("Unexpected request $method $path")
                }
            }
            auth = AccountAuth("test://callback", http,
                { secrets.get(it)?.toString(Charsets.UTF_8) },
                { id, value -> if (value == null) secrets.remove(id) else secrets.put(id, value.toByteArray()) })
            ReflectionHelpers.setField(app, "accountHttp\$delegate", lazyOf(http))
            ReflectionHelpers.setField(app.syncAuth, "auth", auth)
            val client = app.deviceKeyClient
            ReflectionHelpers.setField(app, "deviceKeyStore\$delegate", lazyOf(DeviceKeyStore(secrets)))
            ReflectionHelpers.setField(app, "deviceApproval\$delegate", lazyOf(DeviceApproval(client) { host, command ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                commands.add(host to command)
                assertFalse(requests.contains("POST /v1/device-keys/laptop/approve"))
                if (blockHost) assertTrue(releaseHost.await(15, TimeUnit.SECONDS))
                if (host.id == failingHost) throw IOException("unreachable")
                true
            }))
            model = MainViewModel(app).also { models.put("main", it) }
            profiles.forEach(app.hostStore::upsert)
        }
    }

    private fun settled() {
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !model.state.value.accountSyncing && !model.state.value.deviceApprovalBusy &&
                !ReflectionHelpers.getField<Boolean>(model, "deviceKeyRefreshRunning")
        }
    }

    private fun sync() {
        compose.runOnIdle { model.syncAccount() }
        settled()
    }

    @Test fun signedInRefreshPublishesOnlyPublicKeyAndShowsOtherPendingDeviceOnEveryScreen() {
        setup()
        sync()
        val publicKey = DeviceKeyStore(secrets).ensure()
        assertEquals(setOf("public_key"), published.single().keys().asSequence().toSet())
        assertEquals(publicKey, published.single().getString("public_key"))
        assertFalse(published.single().toString().contains("PRIVATE"))
        assertTrue(secrets.get(DeviceKeyStore.SECRET_ID)!!.toString(Charsets.UTF_8).contains("PRIVATE KEY"))
        assertEquals(PendingDevice(other.deviceId, other.name, sshKeyFingerprint(other.publicKey)), model.state.value.pendingDevice)
        assertTrue(requests.indexOf("PUT /v1/device-key") < requests.indexOf("GET /v1/device-keys"))
        compose.setContent { MultipromptTheme { MultipromptApp(model) } }
        for (section in AppSection.entries) {
            compose.runOnIdle { model.select(section) }
            compose.onNodeWithText("Approve this device?").assertIsDisplayed()
            compose.onNodeWithText(sshKeyFingerprint(other.publicKey)).assertIsDisplayed()
        }
    }

    @Test fun completedSignInPublishesAndListsDeviceKeys() {
        setup(signedIn = false)
        compose.runOnIdle {
            val state = java.net.URI(auth.authorizationUrl("This phone")).rawQuery.split('&')
                .single { it.startsWith("state=") }.substringAfter('=')
            model.onAuthRedirect(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("test://callback?state=$state&code=${"c".repeat(43)}")))
        }
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            model.state.value.pendingDevice != null
        }
        settled()
        assertTrue(model.state.value.signedIn)
        assertEquals(1, published.size)
    }

    @Test fun ownPendingDeviceNeverPrompts() {
        entries = listOf(own, other.copy(status = DeviceKeyStatus.APPROVED))
        setup()
        sync()
        assertNull(model.state.value.pendingDevice)
        assertTrue(commands.isEmpty())
    }

    @Test fun approveAddsKeyToEverySavedHostBeforeApprovalAndRelists() {
        setup()
        sync()
        assertTrue(model.state.value.hosts.isEmpty())
        compose.setContent { MultipromptTheme { MultipromptApp(model) } }
        compose.onNodeWithText("Approve").performClick()
        settled()
        assertEquals(profiles, commands.map { it.first })
        assertTrue(requests.contains("POST /v1/device-keys/laptop/approve"))
        assertEquals(2, requests.count { it == "GET /v1/device-keys" })
        assertNull(model.state.value.pendingDevice)
        assertNull(model.state.value.deviceApprovalError)
        compose.onNodeWithText("Approve this device?").assertDoesNotExist()
    }

    @Test fun failingHostKeepsPromptAndNamesServerWithoutCallingApprove() {
        failingHost = profiles.first().id
        setup()
        sync()
        compose.runOnIdle { model.approveDevice() }
        settled()
        assertEquals(profiles, commands.map { it.first })
        assertFalse(requests.contains("POST /v1/device-keys/laptop/approve"))
        assertEquals("Could not add it to Contabo UK. Try again.", model.state.value.deviceApprovalError)
        assertEquals(other.deviceId, model.state.value.pendingDevice?.id)
        assertTrue(model.state.value.signedIn)
    }

    @Test fun noDeniesAndClearsWithoutSsh() {
        setup()
        sync()
        compose.setContent { MultipromptTheme { MultipromptApp(model) } }
        compose.onNodeWithText("No").performClick()
        settled()
        assertTrue(requests.contains("POST /v1/device-keys/laptop/deny"))
        assertTrue(commands.isEmpty())
        assertNull(model.state.value.pendingDevice)
        compose.onNodeWithText("Approve this device?").assertDoesNotExist()
    }

    @Test fun revokedKeysAreRemovedFromAllSavedHostsAndFailuresStayQuiet() {
        entries = listOf(other.copy(status = DeviceKeyStatus.REVOKED), own)
        failingHost = profiles.first().id
        setup()
        sync()
        assertEquals(profiles.map { it to DeviceKeys.removeCommand(other.publicKey) }, commands)
        assertNull(model.state.value.pendingDevice)
        assertNull(model.state.value.deviceApprovalError)
        assertNull(model.state.value.accountError)
        assertTrue(model.state.value.signedIn)
    }

    @Test fun unavailablePublishShowsNothingAndSkipsListing() {
        unavailablePath = "/v1/device-key"
        setup()
        sync()
        assertNull(model.state.value.pendingDevice)
        assertFalse(requests.contains("GET /v1/device-keys"))
        assertTrue(model.state.value.signedIn)
    }

    @Test fun unavailableListingClearsOldPrompt() {
        setup()
        sync()
        assertNotNull(model.state.value.pendingDevice)
        unavailablePath = "/v1/device-keys"
        sync()
        assertNull(model.state.value.pendingDevice)
        assertTrue(model.state.value.signedIn)
    }

    @Test fun networkErrorsNeverClearLoginAndRetainExistingPrompt() {
        offlinePath = "/v1/device-key"
        setup()
        sync()
        assertNull(model.state.value.pendingDevice)
        assertTrue(auth.hasSession())
        assertTrue(model.state.value.signedIn)
        offlinePath = null
        sync()
        val prompt = model.state.value.pendingDevice
        assertNotNull(prompt)
        offlinePath = "/v1/device-keys"
        sync()
        assertEquals(prompt, model.state.value.pendingDevice)
        assertNull(model.state.value.deviceApprovalError)
        assertTrue(auth.hasSession())
        assertTrue(model.state.value.signedIn)
    }

    @Test fun repeatedRefreshHasOnlyOneInFlightListing() {
        blockList = true
        setup()
        compose.runOnIdle { model.syncAccount() }
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            requests.contains("GET /v1/device-keys")
        }
        repeat(3) {
            compose.runOnIdle { model.syncAccount() }
            compose.waitUntil(15_000) {
                shadowOf(Looper.getMainLooper()).idle()
                !model.state.value.accountSyncing
            }
        }
        assertEquals(1, requests.count { it == "GET /v1/device-keys" })
        releaseList.countDown()
        settled()
        assertNotNull(model.state.value.pendingDevice)
    }

    @Test fun approvalIsBusyAndCannotBeDuplicatedOrDeniedWhileRunning() {
        blockHost = true
        setup()
        sync()
        compose.runOnIdle {
            model.approveDevice()
            assertTrue(model.state.value.deviceApprovalBusy)
            model.approveDevice()
            model.denyDevice()
            model.syncAccount()
        }
        compose.waitUntil(15_000) { commands.isNotEmpty() }
        assertTrue(model.state.value.deviceApprovalBusy)
        releaseHost.countDown()
        settled()
        assertEquals(profiles, commands.map { it.first })
        assertEquals(1, requests.count { it == "POST /v1/device-keys/laptop/approve" })
        assertFalse(requests.contains("POST /v1/device-keys/laptop/deny"))
    }

    @Test fun signedOutRefreshDoesNotPublishOrList() {
        setup(signedIn = false)
        sync()
        assertFalse(model.state.value.signedIn)
        assertTrue(requests.isEmpty())
        assertNull(model.state.value.pendingDevice)
    }

    @Test fun signOutDiscardsInFlightListing() {
        blockList = true
        setup()
        compose.runOnIdle { model.syncAccount() }
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            requests.contains("GET /v1/device-keys")
        }
        compose.runOnIdle { model.signOutAccount() }
        releaseList.countDown()
        settled()
        assertFalse(model.state.value.signedIn)
        assertNull(model.state.value.pendingDevice)
        assertNull(model.state.value.deviceApprovalError)
    }
}
