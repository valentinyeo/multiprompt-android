package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DeviceKeysTest {
    private val key = "ssh-ed25519 AAAA+/=="

    @Test fun parsesPublicKeyAndDropsCommentsLikeDesktop() {
        assertEquals(key, DeviceKeys.parsePublicKey(" \tssh-ed25519\tAAAA+/== multiprompt\r\n"))
        assertEquals("ssh-ed25519 AAAA", DeviceKeys.parsePublicKey("ssh-ed25519 AAAA comment'\"; touch /tmp/unwanted\n"))
        for (invalid in listOf("", "ssh-ed25519", "ssh-rsa AAAA comment", "ssh-ed25519 AA' comment", "ssh-ed25519 BEGIN")) {
            assertNull(DeviceKeys.parsePublicKey(invalid))
        }
        assertTrue(DeviceKeys.validKey("ssh-ed25519 A"))
        assertTrue(DeviceKeys.validKey("ssh-ed25519 " + "A".repeat(188)))
        assertFalse(DeviceKeys.validKey("ssh-ed25519 " + "A".repeat(189)))
    }

    @Test fun injectionPayloadsCannotReachCommands() {
        val invalid = listOf("", "ssh-rsa AAAA", "ssh-ed25519 ", "ssh-ed25519 A=A", "ssh-ed25519 AAA===",
            "ssh-ed25519 AAA'", "ssh-ed25519 AAA\"", "ssh-ed25519 AAA\n", "ssh-ed25519 AAA;id",
            "ssh-ed25519 BEGIN", "ssh-ed25519 PRIVATE", "ssh-ed25519 AAAA comment'\";id", "ssh-ed25519 AAAA\r",
            "ssh-ed25519 " + "A".repeat(189), "ssh-ed25519 ÅAAA")
        for (value in invalid) {
            assertFalse(value, DeviceKeys.validKey(value))
            assertThrows(IllegalArgumentException::class.java) { DeviceKeys.addCommand(value) }
            assertThrows(IllegalArgumentException::class.java) { DeviceKeys.removeCommand(value) }
        }
        val parsed = DeviceKeys.parsePublicKey("ssh-ed25519 AAAA comment'\"; touch /tmp/unwanted\n")!!
        for (command in listOf(DeviceKeys.addCommand(parsed), DeviceKeys.removeCommand(parsed))) {
            assertFalse(command.contains("unwanted"))
            assertFalse(command.contains('\n'))
            assertTrue(DeviceKeys.validCommand(command))
            assertFalse(DeviceKeys.validCommand("$command; id"))
        }
    }

    @Test fun commandsMatchDesktopExactly() {
        assertEquals("umask 077 && mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && if awk -v t=ssh-ed25519 -v k='AAAA' '\$1 == t && \$2 == k { found=1 } END { exit found ? 0 : 3 }' ~/.ssh/authorized_keys; then :; else r=\$?; [ \$r = 3 ] && printf '\\n%s\\n' 'ssh-ed25519 AAAA' >> ~/.ssh/authorized_keys; fi",
            DeviceKeys.addCommand("ssh-ed25519 AAAA"))
        assertEquals("umask 077 && if [ -f ~/.ssh/authorized_keys ]; then tmp=\$(mktemp ~/.ssh/authorized_keys.XXXXXX) && trap 'rm -f \"\$tmp\"' EXIT && awk -v t=ssh-ed25519 -v k='AAAA' '\$1 != t || \$2 != k' ~/.ssh/authorized_keys > \"\$tmp\" && mv \"\$tmp\" ~/.ssh/authorized_keys; fi",
            DeviceKeys.removeCommand("ssh-ed25519 AAAA"))
    }

    @Test fun commandsAreIdempotentCompareFieldsAndPreserveOtherKeys() {
        val home = Files.createTempDirectory("device-key-command").toFile()
        fun execute(command: String) {
            val process = ProcessBuilder("sh", "-c", command).apply { environment()["HOME"] = home.path }
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        try {
            execute(DeviceKeys.removeCommand(key))
            execute(DeviceKeys.addCommand(key))
            execute(DeviceKeys.addCommand(key))
            val authorized = home.resolve(".ssh/authorized_keys")
            assertEquals(listOf(key), authorized.readLines().filter(String::isNotBlank))
            authorized.writeText("$key desktop comment\nssh-ed25519 OTHER keep\nssh-rsa AAAA keep too\n")
            execute(DeviceKeys.addCommand(key))
            assertEquals(3, authorized.readLines().size)
            execute(DeviceKeys.removeCommand(key))
            execute(DeviceKeys.removeCommand(key))
            assertEquals("ssh-ed25519 OTHER keep\nssh-rsa AAAA keep too\n", authorized.readText())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun parsesExactApiListingAndRejectsMalformedValues() {
        val statuses = listOf("pending", "approved", "denied", "revoked")
        val json = JSONObject().put("device_id", "self").put("keys", JSONArray(statuses.map { status ->
            JSONObject().put("device_id", "other_$status").put("name", "Laptop").put("public_key", key)
                .put("status", status).put("requested", 1).put("decided", JSONObject.NULL)
        }))
        val listing = DeviceKeys.parseListing(json)!!
        assertEquals("self", listing.deviceId)
        assertEquals(DeviceKeyStatus.entries, listing.keys.map { it.status })
        assertEquals("Laptop", listing.keys[0].name)
        assertEquals(key, listing.keys[0].publicKey)
        for (id in listOf("", "../other", "a/b", "a?b", "é", "a".repeat(129))) {
            assertFalse(DeviceKeys.validDeviceId(id))
            assertNull(DeviceKeys.parseListing(JSONObject(json.toString()).put("device_id", id)))
            val changed = JSONObject(json.toString())
            changed.getJSONArray("keys").getJSONObject(0).put("device_id", id)
            assertNull(DeviceKeys.parseListing(changed))
        }
        assertTrue(DeviceKeys.validDeviceId("a-Z_09"))
        assertTrue(DeviceKeys.validDeviceId("a".repeat(128)))
        for ((field, value) in listOf("public_key" to "ssh-ed25519 AAA';id", "status" to "PENDING", "status" to "unknown",
            "name" to 2, "public_key" to JSONObject.NULL, "device_id" to 1)) {
            val changed = JSONObject(json.toString())
            changed.getJSONArray("keys").getJSONObject(0).put(field, value)
            assertNull(DeviceKeys.parseListing(changed))
        }
        for (value in listOf(JSONObject(), JSONObject().put("device_id", "self").put("keys", "not an array"),
            JSONObject().put("device_id", "self").put("keys", JSONArray().put(1)))) {
            assertNull(DeviceKeys.parseListing(value))
        }
        assertEquals(emptyList<DeviceKeyEntry>(), DeviceKeys.parseListing(JSONObject().put("device_id", "self").put("keys", JSONArray()))!!.keys)
        assertTrue(DeviceKeys.isCleaned("ssh-ed25519 AAAA\n", "ssh-ed25519 AAAA"))
        assertFalse(DeviceKeys.isCleaned("ssh-ed25519 AAAAB\n", "ssh-ed25519 AAAA"))
    }

    @Test fun injectionCannotBypassSshFixedTemplateAllowlist() = runBlocking {
        val repo = SshRepository(MemoryDeviceSecrets())
        val host = HostProfile(label = "test", hostname = "unreachable.invalid", username = "test")
        for (command in listOf("id", DeviceKeys.addCommand(key) + "; id", DeviceKeys.removeCommand(key).replace("mv ", "cat "))) {
            try {
                repo.runDeviceKeyCommand(host, command)
                fail("Invalid command reached SSH")
            } catch (expected: IllegalArgumentException) {
                assertEquals("Invalid device key command", expected.message)
            }
        }
    }
}
