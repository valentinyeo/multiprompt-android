package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class AccountHostsTest {
    private val key = "ssh-ed25519 " + Base64.getEncoder().encodeToString("public-host-key".toByteArray())
    private fun host(slot: Int = 0) = AccountHost(slot, "Account server", "example.com", 2222, "valentin", listOf(key))
    private fun local() = HostProfile(label = "Device server", hostname = "device.example", username = "bob",
        keySecretId = "private-key-data", passphraseSecretId = "private-passphrase", hostKeyType = "ssh-rsa", hostKeyFingerprint = "SHA256:device")

    @Test fun freshStoreGainsProfilesAndPersistentSlotMappingsWithoutCredentials() {
        val remote = listOf(host(), host(1).copy(name = "Second", hostname = "2001:db8::1", user = "other"))
        val result = AccountHosts.merge(AccountHosts.parse(JSONArray(remote.map { it.json() })), emptyList(), emptyMap())
        assertEquals(2, result.profiles.size)
        for (account in remote) {
            val profile = AccountHostMap.resolve(result.profiles, result.overrides).getValue(account.slot)
            assertEquals(account.name, profile.label)
            assertEquals(account.hostname, profile.hostname)
            assertEquals(account.port, profile.port)
            assertEquals(account.user, profile.username)
            assertEquals(account.hostKeys, profile.hostKeys)
            assertEquals("ssh-ed25519", profile.hostKeyType)
            assertTrue(profile.hostKeyFingerprint!!.startsWith("SHA256:"))
            assertEquals(profile.id, UUID.fromString(profile.id).toString())
            assertEquals("host_key_${profile.id}", profile.keySecretId)
            assertNull(profile.passphraseSecretId)
        }
        assertEquals(result, AccountHosts.merge(remote, result.profiles, result.overrides))
    }

    @Test fun existingMappedProfileIsUntouchedIncludingEndpointCredentialsAndTrust() {
        val existing = local().copy(username = "valentin")
        val result = AccountHosts.merge(listOf(host()), listOf(existing), mapOf(0 to existing.id))
        assertEquals(listOf(existing), result.profiles)
        assertSame(existing, result.profiles.single())
        assertEquals(existing, AccountHostMap.resolve(result.profiles, result.overrides)[0])
        val inferred = existing.copy(label = "Hetzner", username = "valentin")
        assertEquals(listOf(inferred), AccountHosts.merge(listOf(host()), listOf(inferred), emptyMap()).profiles)
    }

    @Test fun sameHostnamePortAndUserReuseProfileWithoutMappingItToTwoSlots() {
        val existing = local().copy(hostname = "EXAMPLE.com", port = 2222, username = "valentin")
        val result = AccountHosts.merge(listOf(host(), host(1)), listOf(existing), mapOf(0 to "deleted"))
        assertEquals(listOf(existing), result.profiles)
        assertEquals(mapOf(0 to existing.id), result.overrides)
        assertEquals(mapOf(0 to existing), AccountHostMap.resolve(result.profiles, result.overrides))
        val occupied = AccountHosts.merge(listOf(host(1)), listOf(existing), mapOf(2 to existing.id))
        assertEquals(listOf(existing), occupied.profiles)
        assertEquals(mapOf(2 to existing.id), occupied.overrides)
        for (different in listOf(existing.copy(port = 22), existing.copy(username = "bob"))) {
            assertEquals(2, AccountHosts.merge(listOf(host()), listOf(different), emptyMap()).profiles.size)
        }
    }

    @Test fun slotZeroWithAnotherUserIsNeitherImportedNorMapped() {
        val remote = host().copy(user = "alice")
        val fresh = AccountHosts.merge(listOf(remote), emptyList(), emptyMap())
        assertTrue(fresh.profiles.isEmpty())
        assertTrue(fresh.overrides.isEmpty())
        val existing = local().copy(hostname = remote.hostname, port = remote.port, username = remote.user)
        val result = AccountHosts.merge(listOf(remote), listOf(existing), emptyMap())
        assertEquals(listOf(existing), result.profiles)
        assertTrue(result.overrides.isEmpty())
        assertTrue(AccountHostMap.resolve(result.profiles, result.overrides).isEmpty())
    }

    @Test fun publishingKeepsAccountEntriesUnchangedAndOnlyFillsMissingSlots() {
        val keys = listOf(key, "ssh-rsa " + Base64.getEncoder().encodeToString("second-key".toByteArray()),
            "ecdsa-sha2-nistp256 " + Base64.getEncoder().encodeToString("third-key".toByteArray()))
        val account = host().copy(name = "Hetzner", hostname = "VPS.example", hostKeys = keys)
        val phone = local().copy(label = "vps", hostname = "vps.example", username = "valentin", hostKeys = listOf(key))
        val missing = local()
        val outgoing = AccountHosts.publish(listOf(account), mapOf(0 to phone, 1 to missing))
        assertEquals(listOf(account, AccountHosts.outgoing(mapOf(1 to missing)).single()), outgoing)
        assertSame(account, outgoing[0])
        assertEquals(listOf(account), AccountHosts.parse(AccountLayout(hosts = outgoing).json().getJSONArray("hosts")).filter { it.slot == 0 })
    }

    @Test fun invalidEntriesAreIgnoredWithoutDiscardingValidNeighbors() {
        val invalid = listOf(
            host().json().put("slot", -1), host().json().put("slot", 3), host().json().put("slot", 0.5),
            host().json().put("slot", java.math.BigDecimal("0.00000000000000000000001")),
            host().json().put("port", java.math.BigDecimal("22.00000000000000000000001")),
            host().json().put("slot", "0"), host().json().put("port", 0), host().json().put("port", 65536),
            host().json().put("port", 22.5), host().json().put("port", "22"),
            host().json().put("name", ""), host().json().put("name", "x".repeat(33)),
            host().json().put("name", "bad\nname"), host().json().put("hostname", "bad/host"),
            host().json().put("hostname", "x".repeat(254)), host().json().put("hostname", JSONObject.NULL),
            host().json().put("user", "-root"), host().json().put("user", "root;sh"),
            host().json().put("user", "a".repeat(33)), host().json().put("private_key", "private-key-data"),
            host().json().put("host_keys", "not an array"), host().json().put("host_keys", JSONArray().put(12)),
            host().json().put("host_keys", JSONArray(listOf(key, key))),
            host().json().put("host_keys", JSONArray(List(5) { "$key$it" })),
            host().json().put("host_keys", JSONArray(listOf("ssh-ed25519 invalid!"))),
            host().json().put("host_keys", JSONArray(listOf("PRIVATE KEY data"))),
            host().json().apply { remove("user") }, JSONObject(), JSONObject.NULL, "not an object",
        )
        for (bad in invalid) {
            val parsed = AccountHosts.parse(JSONArray().put(bad).put(host(2).json()))
            assertEquals("bad=$bad", listOf(host(2)), parsed)
        }
        for (bad in listOf(null, JSONObject.NULL, "[]", JSONObject(), JSONArray(List(4) { host(it % 3).json() }))) {
            assertTrue(AccountHosts.parse(bad).isEmpty())
        }
        assertEquals(listOf(host()), AccountHosts.parse(JSONArray().put(host().json()).put(host().copy(name = "Duplicate").json())))
    }

    @Test fun outgoingLayoutContainsOnlyMappedPublicHostMetadataAndNeverPrivateKeyData() {
        val profile = local().copy(hostKeys = listOf(key))
        val outgoing = AccountHosts.outgoing(mapOf(2 to profile))
        val json = AccountLayout(hosts = outgoing).json()
        val entry = json.getJSONArray("hosts").getJSONObject(0)
        assertEquals(setOf("slot", "name", "hostname", "port", "user", "host_keys"), entry.keys().asSequence().toSet())
        assertEquals(2, entry.getInt("slot"))
        assertEquals(profile.label, entry.getString("name"))
        assertEquals(profile.hostname, entry.getString("hostname"))
        assertEquals(profile.port, entry.getInt("port"))
        assertEquals(profile.username, entry.getString("user"))
        assertEquals(key, entry.getJSONArray("host_keys").getString(0))
        fun containsSecret(value: JSONObject) = listOf(profile.keySecretId, profile.passphraseSecretId!!).any { it in value.toString() }
        assertFalse(containsSecret(json))
        assertTrue(containsSecret(JSONObject(json.toString()).put("private_key", profile.keySecretId)))
        assertFalse(AccountHosts.outgoing(mapOf(0 to local())).single().json().has("host_keys"))
        assertTrue(AccountHosts.outgoing(mapOf(0 to local().copy(username = "invalid user"))).isEmpty())
        assertEquals(AccountLayout(hosts = outgoing), AccountLayout.parse(json))
        val pending = PendingLayout(AccountLayout(), AccountLayout(hosts = outgoing))
        assertTrue(pending.dirty)
        assertTrue(UsedStampThrottle().shouldPush(pending))
        assertEquals(pending, PendingLayout.parse(pending.json()))
        assertEquals(listOf(host(0), host(2)), AccountHosts.publish(listOf(host(2)), mapOf(0 to
            HostProfile(label = host().name, hostname = host().hostname, port = host().port, username = host().user, hostKeys = listOf(key)))))
    }

    @Test fun getAndPutNegotiateHostsWithoutDroppingExistingFeatures() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request().also(requests::add)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()
        try {
            val http = AccountHttpClient(client)
            http.request("GET", "/v1/layout", "token", null)
            http.request("PUT", "/v1/layout", "token", AccountLayout(hosts = listOf(host())).json())
            assertEquals(listOf("GET", "PUT"), requests.map { it.method })
            requests.forEach { request ->
                assertEquals(setOf("favorite", "used", "visits", "input", "settings", "hosts"), request.header("X-Layout-Features")!!.split(',').toSet())
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
