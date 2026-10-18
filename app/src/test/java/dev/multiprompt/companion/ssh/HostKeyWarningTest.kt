package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostKeyWarningTest {
    private val host = HostProfile(
        label = "Inne Agent",
        hostname = "157.173.109.227",
        username = "agent",
        keySecretId = "key",
        hostKeyType = "ssh-ed25519",
        hostKeyFingerprint = "SHA256:4nGhs+5T5yUDbuIO1/CrTtJj24LRq9Dm4m4DcQeKNic",
    )
    private val key = PresentedHostKey(
        type = "ssh-ed25519",
        fingerprint = "SHA256:J/MLUSXxo9y2KhRMODfm12lStdjHDdaxQhq3vdcpRYo",
        changed = true,
    )

    @Test
    fun changedKeyNamesTheServerAndBothKeys() {
        val text = HostKeyWarning.text(host, key)

        assertEquals("SSH host key changed", text.title)
        assertEquals(
            "The saved identity for Inne Agent no longer matches. Only continue if you intentionally changed the server key.",
            text.body,
        )
        assertEquals("agent@157.173.109.227", text.server)
        assertEquals("Saved: ssh-ed25519 SHA256:4nGhs+5T5yUDbuIO1/CrTtJj24LRq9Dm4m4DcQeKNic", text.saved)
        assertEquals("Now: ssh-ed25519 SHA256:J/MLUSXxo9y2KhRMODfm12lStdjHDdaxQhq3vdcpRYo", text.presented)
        assertEquals("Replace trusted key", text.confirm)
    }

    @Test
    fun nonDefaultPortIsIncludedInTheServer() {
        val text = HostKeyWarning.text(host.copy(port = 2222), key)

        assertEquals("agent@157.173.109.227:2222", text.server)
    }

    @Test
    fun firstConnectionHasNoSavedKey() {
        val text = HostKeyWarning.text(
            host.copy(hostKeyType = null, hostKeyFingerprint = null),
            key.copy(changed = false),
        )

        assertEquals("Trust Inne Agent?", text.title)
        assertEquals("Inne Agent presented this identity on the first connection:", text.body)
        assertEquals("agent@157.173.109.227", text.server)
        assertNull(text.saved)
        assertEquals("ssh-ed25519 SHA256:J/MLUSXxo9y2KhRMODfm12lStdjHDdaxQhq3vdcpRYo", text.presented)
        assertEquals("Trust and connect", text.confirm)
    }

    @Test
    fun changedKeyWithoutSavedFingerprintHasNoSavedLine() {
        val text = HostKeyWarning.text(host.copy(hostKeyFingerprint = null), key)

        assertNull(text.saved)
    }

    @Test
    fun missingHostUsesFallbackServerAndLabel() {
        val text = HostKeyWarning.text(null, key.copy(changed = false))

        assertEquals("unknown server", text.server)
        assertEquals("Trust SSH host?", text.title)
        assertEquals("SSH host presented this identity on the first connection:", text.body)
        assertNull(text.saved)
    }

    @Test
    fun missingHostWithChangedKeyHasNoSavedLine() {
        val text = HostKeyWarning.text(null, key)

        assertEquals("unknown server", text.server)
        assertEquals("SSH host key changed", text.title)
        assertNull(text.saved)
        assertEquals("Now: ssh-ed25519 SHA256:J/MLUSXxo9y2KhRMODfm12lStdjHDdaxQhq3vdcpRYo", text.presented)
    }
}
