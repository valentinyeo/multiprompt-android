package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DeviceKeyApprovalTest {
    private val entry = DeviceKeyEntry("other", "Laptop", "ssh-ed25519 AAAA", DeviceKeyStatus.PENDING)
    private val hosts = (1..3).map { HostProfile(id = "host$it", label = "Host $it", hostname = "host$it.invalid", username = "test") }
    private val decisions = mutableListOf<String>()
    private var status = 200
    private val client = DeviceKeyClient(AccountHttp { method, path, _, body ->
        assertEquals("POST", method)
        assertEquals("{}", body.toString())
        decisions.add(path)
        AccountReply(status, JSONObject().put("status", if (path.endsWith("/approve")) "approved" else "denied"))
    }) { "token" }

    @Test fun keyChangesRemoveKeysFromEveryHostWhenNotApproved() = runBlocking {
        val installed = mutableSetOf<String>()
        val ran = mutableListOf<Pair<String, String>>()
        val add = DeviceKeys.addCommand(entry.publicKey)
        val remove = DeviceKeys.removeCommand(entry.publicKey)
        val approval = DeviceApproval(client) { host, command ->
            assertTrue(decisions.isEmpty())
            ran.add(host.id to command)
            when (command) {
                add -> {
                    installed.add(host.id)
                    host != hosts[1]
                }
                remove -> {
                    installed.remove(host.id)
                    true
                }
                else -> error("Unexpected command")
            }
        }
        val result = approval.approve(entry, hosts)
        assertFalse(result.approved)
        assertEquals(listOf(hosts[1]), result.failures.map { it.host })
        assertEquals(hosts.map { it.id to add } + hosts.map { it.id to remove }, ran)
        assertTrue(installed.isEmpty())
        assertTrue(decisions.isEmpty())

        installed.addAll(hosts.map { it.id })
        ran.clear()
        assertTrue(approval.deny(entry, hosts))
        assertEquals(hosts.map { it.id to remove }, ran)
        assertTrue(installed.isEmpty())
        assertEquals(listOf("/v1/device-keys/other/deny"), decisions)

        decisions.clear()
        installed.addAll(hosts.map { it.id })
        ran.clear()
        val listing = DeviceKeyListing("self", listOf(entry.copy(status = DeviceKeyStatus.DENIED)))
        assertTrue(approval.cleanupRevoked(listing, hosts).isEmpty())
        assertEquals(hosts.map { it.id to remove }, ran)
        assertTrue(installed.isEmpty())
        assertTrue(decisions.isEmpty())
    }

    @Test fun approveInstallsEveryHostBeforeServerDecision() = runBlocking {
        val ran = mutableListOf<HostProfile>()
        val approval = DeviceApproval(client) { host, command ->
            assertTrue(decisions.isEmpty())
            assertEquals(DeviceKeys.addCommand(entry.publicKey), command)
            ran.add(host)
            true
        }
        val result = approval.approve(entry, hosts)
        assertTrue(result.approved)
        assertTrue(result.failures.isEmpty())
        assertEquals(hosts, ran)
        assertEquals(listOf("/v1/device-keys/other/approve"), decisions)
    }

    @Test fun approveReportsEveryFailedHostAndNeverDecidesUntilRetrySucceeds() = runBlocking {
        val ran = mutableListOf<HostProfile>()
        val approval = DeviceApproval(client) { host, _ ->
            ran.add(host)
            if (host == hosts[0]) throw IOException("offline")
            host != hosts[1]
        }
        val result = approval.approve(entry, hosts)
        assertFalse(result.approved)
        assertEquals(hosts, ran)
        assertEquals(hosts.take(2), result.failures.map { it.host })
        assertEquals(listOf("other", "other"), result.failures.map { it.deviceId })
        assertEquals("offline", result.failures[0].message)
        assertTrue(result.failures[1].message.isNotEmpty())
        assertTrue(decisions.isEmpty())
        assertTrue(DeviceApproval(client) { _, _ -> true }.approve(entry, hosts).approved)
        assertEquals(1, decisions.size)
    }

    @Test fun approveAndDenyHandle403404409() = runBlocking {
        val approval = DeviceApproval(client) { _, _ -> true }
        for (response in listOf(403, 404, 409)) {
            status = response
            val result = approval.approve(entry, hosts)
            assertFalse(result.approved)
            assertTrue(result.failures.isEmpty())
            assertFalse(approval.deny(entry, hosts))
        }
    }

    @Test fun denyNeverRunsAHostCommand() {
        val approval = DeviceApproval(client) { _, _ -> error("Deny cannot execute SSH") }
        assertTrue(runBlocking { approval.deny(entry, hosts) })
        assertEquals(listOf("/v1/device-keys/other/deny"), decisions)
    }

    @Test fun revokeCleanupRemovesEveryRevokedKeyOnEveryHostAndReportsFailures() = runBlocking {
        val revoked = listOf(entry.copy(status = DeviceKeyStatus.REVOKED), entry.copy(deviceId = "second", publicKey = "ssh-ed25519 BBBB", status = DeviceKeyStatus.REVOKED))
        val listing = DeviceKeyListing("self", revoked + listOf(entry, entry.copy(status = DeviceKeyStatus.APPROVED), entry.copy(status = DeviceKeyStatus.DENIED)))
        val ran = mutableListOf<Pair<String, String>>()
        val approval = DeviceApproval(client) { host, command ->
            ran.add(host.id to command)
            if (host == hosts[0]) throw IOException("unreachable")
            host != hosts[1]
        }
        val failures = approval.cleanupRevoked(listing, hosts)
        assertEquals(revoked.flatMap { key -> hosts.map { it.id to DeviceKeys.removeCommand(key.publicKey) } }, ran)
        assertEquals(4, failures.size)
        assertEquals(listOf("other", "other", "second", "second"), failures.map { it.deviceId })
        assertTrue(decisions.isEmpty())
        assertTrue(DeviceApproval(client) { _, _ -> true }.cleanupRevoked(listing, hosts).isEmpty())
        assertTrue(approval.cleanupRevoked(listing, emptyList()).isEmpty())
    }

    @Test fun injectionAndNonPendingEntriesCannotReachHostCommandsOrApproval() = runBlocking {
        var ran = false
        val approval = DeviceApproval(client) { _, _ -> ran = true; true }
        for (key in listOf("ssh-ed25519 AAA'", "ssh-ed25519 AAA\"", "ssh-ed25519 AAA;id", "ssh-ed25519 AAA\n", "ssh-ed25519 AAAA comment'\";id")) {
            try {
                approval.approve(entry.copy(publicKey = key), hosts)
                fail("Invalid key was approved")
            } catch (_: IllegalArgumentException) { }
            try {
                approval.cleanupRevoked(DeviceKeyListing("self", listOf(entry.copy(publicKey = key, status = DeviceKeyStatus.REVOKED))), hosts)
                fail("Invalid key reached cleanup")
            } catch (_: IllegalArgumentException) { }
        }
        for (invalid in listOf(entry.copy(deviceId = "../other"), entry.copy(status = DeviceKeyStatus.APPROVED))) {
            try {
                approval.approve(invalid, hosts)
                fail("Invalid approval")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            approval.approve(entry, emptyList())
            fail("No hosts cannot approve")
        } catch (_: IllegalArgumentException) { }
        assertFalse(ran)
        assertTrue(decisions.isEmpty())
    }

    @Test fun approveAndRevokePropagateCancellationWithoutDeciding() = runBlocking {
        val approval = DeviceApproval(client) { _, _ -> throw CancellationException("cancelled") }
        try {
            approval.approve(entry, hosts)
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        try {
            approval.cleanupRevoked(DeviceKeyListing("self", listOf(entry.copy(status = DeviceKeyStatus.REVOKED))), hosts)
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        assertTrue(decisions.isEmpty())
    }
}
