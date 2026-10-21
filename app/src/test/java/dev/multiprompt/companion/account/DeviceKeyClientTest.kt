package dev.multiprompt.companion.account

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeviceKeyClientTest {
    private val key = "ssh-ed25519 AAAA"
    private data class Request(val method: String, val path: String, val token: String?, val body: JSONObject?)
    private val requests = mutableListOf<Request>()
    private var reply = AccountReply(200, JSONObject().put("status", "pending"))
    private var failure: IOException? = null
    private val http = AccountHttp { method, path, token, body ->
        requests.add(Request(method, path, token, body))
        failure?.let { throw it }
        reply
    }
    private val client = DeviceKeyClient(http) { "account-token" }

    @Test fun publishesOnlyPublicKeyAndNeverStoredPrivateBytes() {
        val secrets = MemoryDeviceSecrets()
        val publicKey = DeviceKeyStore(secrets).ensure()
        assertEquals(DeviceKeyStatus.PENDING, client.publish(publicKey))
        val request = requests.single()
        assertEquals("PUT", request.method)
        assertEquals("/v1/device-key", request.path)
        assertEquals("account-token", request.token)
        assertEquals(1, request.body!!.length())
        assertEquals(setOf("public_key"), request.body.keys().asSequence().toSet())
        assertEquals(publicKey, request.body.get("public_key"))
        assertEquals(JSONObject().put("public_key", publicKey).toString(), request.body.toString())
        reply = AccountReply(200, JSONObject().put("status", "approved"))
        client.approve("other"); reply = AccountReply(200, JSONObject().put("status", "denied"))
        client.deny("other"); reply = AccountReply(200, JSONObject().put("revoked", true))
        client.revoke("other"); reply = AccountReply(200, JSONObject().put("device_id", "self").put("keys", JSONArray()))
        client.list()
        val privateBytes = secrets.get("device_key")!!
        for (sent in requests) {
            val wire = sent.method + sent.path + sent.token + sent.body?.toString().orEmpty()
            assertFalse(wire.contains("PRIVATE"))
            assertFalse(wire.contains("BEGIN"))
            assertFalse(wire.contains(privateBytes.toString(Charsets.UTF_8)))
            assertFalse(wire.contains(Base64.getEncoder().encodeToString(privateBytes)))
            assertFalse(wire.contains(privateBytes.toString(Charsets.UTF_8).lineSequence().filter { !it.startsWith("-----") }.joinToString("")))
        }
        assertArrayEquals(privateBytes, secrets.get("device_key"))
    }

    @Test fun publishParsesEveryStatusAndListUsesExactEndpoint() {
        for (status in DeviceKeyStatus.entries) {
            reply = AccountReply(200, JSONObject().put("status", status.name.lowercase()))
            assertEquals(status, client.publish(key))
        }
        reply = AccountReply(200, JSONObject().put("device_id", "self").put("keys", JSONArray()))
        assertEquals(DeviceKeyListing("self", emptyList()), client.list())
        assertEquals(Request("GET", "/v1/device-keys", "account-token", null), requests.last())
    }

    @Test fun approveDenyAndRevokeHaveExactMethodsPathsAndEmptyDecisionBodies() {
        reply = AccountReply(200, JSONObject().put("status", "approved"))
        assertTrue(client.approve("other_1"))
        assertEquals("POST", requests.last().method)
        assertEquals("/v1/device-keys/other_1/approve", requests.last().path)
        assertEquals("{}", requests.last().body.toString())
        reply = AccountReply(200, JSONObject().put("status", "denied"))
        assertTrue(client.deny("other-2"))
        assertEquals("POST", requests.last().method)
        assertEquals("/v1/device-keys/other-2/deny", requests.last().path)
        assertEquals("{}", requests.last().body.toString())
        reply = AccountReply(200, JSONObject().put("revoked", true))
        assertTrue(client.revoke("other"))
        assertEquals(Request("DELETE", "/v1/devices/other", "account-token", null), requests.last())
    }

    @Test fun unavailable404AndDecision403404409NeverClearLoginOrThrow() {
        val secrets = mutableMapOf(AccountAuth.TOKENS to JSONObject().put("access_token", "stored-token").put("expires_at", Long.MAX_VALUE).toString())
        val auth = AccountAuth("app:callback", http, secrets::get, { id, value -> if (value == null) secrets.remove(id) else secrets[id] = value })
        val authenticated = DeviceKeyClient(http) { auth.accessToken() }
        val before = secrets.toMap()
        reply = AccountReply(404, JSONObject())
        assertNull(authenticated.publish(key))
        assertNull(authenticated.list())
        for (status in listOf(403, 404, 409)) {
            reply = AccountReply(status, JSONObject().put("error", when (status) { 403 -> "self_approval"; 404 -> "not_found"; else -> "not_pending" }))
            assertFalse(authenticated.approve("other"))
            assertFalse(authenticated.deny("other"))
            assertFalse(authenticated.revoke("other"))
        }
        assertTrue(auth.hasSession())
        assertEquals(before, secrets)
    }

    @Test fun invalidInputsAreRejectedBeforeTokenOrHttpAndMalformedResponsesFailClosed() {
        for (value in listOf("../other", "", "x/y", "x?approve", "x\n")) {
            assertThrows(IllegalArgumentException::class.java) { client.approve(value) }
            assertThrows(IllegalArgumentException::class.java) { client.deny(value) }
            assertThrows(IllegalArgumentException::class.java) { client.revoke(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { client.publish("ssh-ed25519 AAAA comment") }
        assertTrue(requests.isEmpty())
        reply = AccountReply(200, JSONObject())
        assertThrows(IOException::class.java) { client.publish(key) }
        assertThrows(IOException::class.java) { client.list() }
        assertThrows(IOException::class.java) { client.approve("other") }
        assertThrows(IOException::class.java) { client.deny("other") }
        assertThrows(IOException::class.java) { client.revoke("other") }
        reply = AccountReply(200, JSONObject().put("status", "approved").put("revoked", "true"))
        assertThrows(IOException::class.java) { client.deny("other") }
        assertThrows(IOException::class.java) { client.revoke("other") }
    }

    @Test fun serviceAndNetworkFailuresPreserveCredentials() {
        for (status in listOf(400, 401, 500)) {
            reply = AccountReply(status, JSONObject().put("error", "invalid_key"))
            assertThrows(IOException::class.java) { client.publish(key) }
            assertThrows(IOException::class.java) { client.list() }
            assertThrows(IOException::class.java) { client.approve("other") }
            assertThrows(IOException::class.java) { client.deny("other") }
            assertThrows(IOException::class.java) { client.revoke("other") }
        }
        failure = IOException("offline")
        assertThrows(IOException::class.java) { client.list() }
    }
}
