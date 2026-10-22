package dev.multiprompt.companion.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HostKeyDismissalSyncTest {
    private val host = SyncSchema.SyncHost(
        id = "host", label = "VPS", hostname = "example.com", port = 22, username = "user", keySecretId = "key",
        hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:saved",
    )

    @Test
    fun dismissedTypeAndFingerprintRoundTripWithoutChangingPinnedKey() {
        val dismissed = host.copy(dismissedHostKeyFingerprint = "ssh-ed25519 SHA256:changed")
        assertEquals(listOf(dismissed), SyncSchema.parseHosts(SyncSchema.hostsBody(listOf(dismissed))))
    }

    @Test
    fun hostsWithoutDismissalKeepTheirExistingWireFormat() {
        val body = SyncSchema.hostsBody(listOf(host))
        assertFalse(JSONObject(body).getJSONArray("hosts").getJSONObject(0).has("dismissedHostKeyFingerprint"))
        assertNull(SyncSchema.parseHosts(body).single().dismissedHostKeyFingerprint)
    }

    @Test
    fun unknownOptionalHostFieldsAreIgnored() {
        val body = JSONObject(SyncSchema.hostsBody(listOf(host)))
        body.getJSONArray("hosts").getJSONObject(0).put("futureOptionalField", "ignored")
        assertEquals(listOf(host), SyncSchema.parseHosts(body.toString()))
    }
}
