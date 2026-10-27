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
import dev.multiprompt.companion.ssh.PresentedHostKey
import dev.multiprompt.companion.ui.HostMappingDialog
import dev.multiprompt.companion.ui.MultipromptTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyFingerprint
import org.connectbot.sshlib.PublicKey
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
import java.util.Base64
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountHostsAppTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var app: MultipromptApplication
    private lateinit var model: MainViewModel
    private lateinit var client: OkHttpClient
    private val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val puts = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val key = "ssh-ed25519 " + Base64.getEncoder().encodeToString("public-host-key".toByteArray())
    private val remoteHost = AccountHost(0, "Account server", "example.com", 2222, "valentin", listOf(key))
    private var remote = AccountLayout(1, hosts = listOf(remoteHost))
    private var conflict = false
    private var features: String? = "favorite,used,visits,input,settings,hosts"
    private var featureHeader = "X-Layout-Features"

    @After fun close() {
        compose.runOnIdle { models.clear() }
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        if (::app.isInitialized) app.deepgramDictation.close()
    }

    private fun setup(profiles: List<HostProfile> = emptyList()) {
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
                val request = chain.request().also(requests::add)
                var status = 200
                if (request.method == "PUT") {
                    val body = JSONObject(Buffer().apply { request.body!!.writeTo(this) }.readUtf8())
                    puts.add(body)
                    if (features?.split(',')?.map { it.trim() }?.contains("hosts") != true && body.getJSONObject("layout").has("hosts")) {
                        status = 400
                    } else if (conflict) {
                        conflict = false
                        remote = remote.copy(revision = remote.revision + 1,
                            hosts = remote.hosts.orEmpty() + remoteHost.copy(slot = 2, name = "Another server", hostname = "another.example"))
                        status = 409
                    } else {
                        assertEquals(remote.revision, body.getLong("base_revision"))
                        val incoming = AccountLayout.parse(body.getJSONObject("layout"))
                        remote = incoming.copy(revision = remote.revision + 1,
                            hosts = if (incoming.hosts == null) remote.hosts else
                                (remote.hosts.orEmpty().associateBy { it.slot } + incoming.hosts.associateBy { it.slot }).values.sortedBy { it.slot })
                    }
                } else assertEquals("GET", request.method)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("OK")
                    .apply { features?.let { header(featureHeader, it) } }
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

    @Test fun signedInFreshInstallPullsHostsIntoPersistentHostListAndRendersServers() {
        setup()
        sync()
        compose.runOnIdle {
            val profile = model.state.value.hosts.single()
            assertEquals(remoteHost.name, profile.label)
            assertEquals(remoteHost.user, profile.username)
            assertEquals(listOf(key), profile.hostKeys)
            assertEquals(profile, HostStore(app).load().single())
            assertEquals(profile.id, AccountLayoutStore(app).hostOverrides()[0])
            assertEquals(profile, model.state.value.accountHosts[0])
            assertEquals(profile, AccountHostMap.resolve(HostStore(app).load(), AccountLayoutStore(app).hostOverrides())[0])
            assertNull(app.secretStore.get(profile.keySecretId))
            model.select(AppSection.SESSIONS)
        }
        compose.setContent { MultipromptTheme { HostMappingDialog(model, onDismiss = {}) } }
        compose.onNodeWithText("0 · Account server: Account server (valentin)").assertIsDisplayed()
        sync()
        compose.runOnIdle { assertEquals(1, HostStore(app).load().size) }
    }

    @Test fun accountEntriesWinAndPhoneOnlyFillsMissingSlotsAcrossConflictAndRetry() {
        featureHeader = "x-LaYoUt-FeAtUrEs"
        features = " favorite, used , visits,input, settings , hosts "
        setup()
        val keys = listOf(key, "ssh-rsa " + Base64.getEncoder().encodeToString("second-key".toByteArray()),
            "ecdsa-sha2-nistp256 " + Base64.getEncoder().encodeToString("third-key".toByteArray()))
        val authoritative = remoteHost.copy(name = "Hetzner", hostname = "VPS.example", hostKeys = keys)
        val local = HostProfile(label = "vps", hostname = "vps.example", port = authoritative.port, username = "valentin",
            keySecretId = "private-key-data", passphraseSecretId = "private-passphrase", hostKeys = listOf(key))
        val missing = HostProfile(label = "Contabo UK", hostname = "contabo.example", username = "bob", hostKeys = listOf(key))
        compose.runOnIdle {
            remote = remote.copy(hosts = listOf(authoritative))
            app.hostStore.upsert(local)
            app.hostStore.upsert(missing)
            AccountLayoutStore(app).setHost(0, local.id)
            AccountLayoutStore(app).setHost(1, missing.id)
            conflict = true
        }
        sync()
        compose.runOnIdle {
            assertEquals(local.copy(label = authoritative.name), app.hostStore.load().first { it.id == local.id })
            assertEquals(local.copy(label = authoritative.name), model.state.value.accountHosts[0])
            assertEquals(2, puts.size)
            assertEquals(listOf("GET", "PUT", "GET", "PUT"), requests.map { it.method })
            puts.forEach { body ->
                val outgoing = AccountHosts.parse(body.getJSONObject("layout").getJSONArray("hosts"))
                assertEquals(authoritative, outgoing.single { it.slot == 0 })
                assertEquals(AccountHost(1, missing.label, missing.hostname, missing.port, missing.username, listOf(key)),
                    outgoing.single { it.slot == 1 })
                assertFalse(body.toString().contains(local.keySecretId))
                assertFalse(body.toString().contains(local.passphraseSecretId!!))
            }
            requests.forEach { assertTrue(it.header("X-Layout-Features")!!.split(',').contains("hosts")) }
            assertEquals(setOf(0, 1, 2), model.state.value.accountHosts.keys)
            assertEquals(authoritative, remote.hosts!!.single { it.slot == 0 })
        }
        sync()
        compose.runOnIdle { assertEquals(2, puts.size) }
    }

    @Test fun serverWithoutHostsAcceptsLayoutPutWithMappedProfiles() {
        features = "favorite,used,visits,input,settings"
        remote = remote.copy(hosts = null)
        val local = HostProfile(label = "Phone server", hostname = "phone.example", username = "valentin")
        setup(listOf(local))
        compose.runOnIdle { AccountLayoutStore(app).setHost(0, local.id) }
        sync()
        compose.runOnIdle {
            assertEquals(local, model.state.value.accountHosts[0])
            assertTrue(puts.isEmpty())
            assertNull(model.editAccountLayout { it.copy(workspaces = listOf("Phone workspace")) })
        }
        sync()
        compose.runOnIdle {
            assertFalse(puts.single().getJSONObject("layout").has("hosts"))
            assertEquals(listOf("Phone workspace"), remote.workspaces)
        }
    }

    @Test fun rollbackToServerWithoutHostsStopsPublishingMappedProfiles() {
        setup()
        sync()
        compose.runOnIdle {
            assertEquals(1, model.state.value.hosts.size)
            features = null
            remote = remote.copy(hosts = null)
        }
        sync()
        compose.runOnIdle {
            assertNull(model.editAccountLayout { it.copy(workspaces = listOf("After rollback")) })
        }
        sync()
        compose.runOnIdle {
            assertFalse(puts.single().getJSONObject("layout").has("hosts"))
            assertEquals(listOf("After rollback"), remote.workspaces)
        }
    }

    @Test fun unadvertisedHostsAreNotImported() {
        features = "favorite,used,visits,input,settings,ghosts"
        setup()
        sync()
        compose.runOnIdle {
            assertTrue(model.state.value.hosts.isEmpty())
            assertTrue(HostStore(app).load().isEmpty())
            assertTrue(puts.isEmpty())
        }
    }

    @Test fun sameEndpointUsesExistingStoredProfileAndRetainsLocalTrust() {
        setup()
        val existing = HostProfile(label = "Local name", hostname = remoteHost.hostname, port = remoteHost.port,
            username = remoteHost.user, keySecretId = "existing-key", hostKeyType = "ssh-rsa", hostKeyFingerprint = "SHA256:local")
        compose.runOnIdle { app.hostStore.upsert(existing) }
        sync()
        compose.runOnIdle {
            assertEquals(listOf(existing.copy(label = remoteHost.name)), HostStore(app).load())
            assertEquals(existing.id, AccountLayoutStore(app).hostOverrides()[0])
            assertEquals(existing.copy(label = remoteHost.name), model.state.value.accountHosts[0])
        }
    }

    @Test fun importedKeysUseExistingVerifierAndUnknownOrChangedKeysStillFailClosed() = runBlocking {
        val second = "ssh-rsa " + Base64.getEncoder().encodeToString("second-public-host-key".toByteArray())
        val profile = AccountHosts.merge(listOf(remoteHost.copy(hostKeys = listOf(key, second))), emptyList(), emptyMap()).profiles.single()
        fun verifier(host: HostProfile): HostKeyVerifier {
            val type = Class.forName("dev.multiprompt.companion.ssh.SshRepository\$PinningHostKeyVerifier")
            return type.getDeclaredConstructor(String::class.java, String::class.java, List::class.java).apply { isAccessible = true }
                .newInstance(host.hostKeyType, host.hostKeyFingerprint, host.hostKeys) as HostKeyVerifier
        }
        fun publicKey(value: String) = PublicKey(value.substringBefore(' '), Base64.getDecoder().decode(value.substringAfter(' ')))
        assertEquals(KeyFingerprint.sha256(publicKey(key).encoded), profile.hostKeyFingerprint)
        assertTrue(verifier(profile).verify(publicKey(key)))
        assertTrue(verifier(profile).verify(publicKey(second)))
        val unknown = PublicKey("ssh-ed25519", "different-key".toByteArray())
        val changed = verifier(profile)
        assertFalse(changed.verify(unknown))
        val presented = ReflectionHelpers.getField<PresentedHostKey>(changed, "presented")
        assertTrue(presented.changed)
        assertEquals("ssh-ed25519 " + Base64.getEncoder().encodeToString(unknown.encoded), presented.publicKey)
        val fresh = verifier(profile.copy(hostKeyType = null, hostKeyFingerprint = null, hostKeys = emptyList()))
        assertFalse(fresh.verify(unknown))
        assertFalse(ReflectionHelpers.getField<PresentedHostKey>(fresh, "presented").changed)
        assertTrue(verifier(profile.copy(hostKeys = emptyList())).verify(publicKey(key)))
    }
}
