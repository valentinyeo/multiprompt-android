package dev.multiprompt.companion.account

import dev.multiprompt.companion.security.SecretStorage
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.connectbot.sshlib.SshKeys
import org.connectbot.sshlib.SshSigning
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.Signature
import java.security.KeyPairGenerator
import java.util.Base64
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Callable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class DeviceKeyTest {
    @Test fun roundTripOpenSshThroughAuthenticationDecoder() {
        val pair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider()).generateKeyPair()
        val encoded = SshKeys.encodeOpenSshPrivateKey(pair).toByteArray(Charsets.UTF_8)
        assertTrue(encoded.toString(Charsets.UTF_8).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        val decoded = SshKeys.decodePemPrivateKey(encoded.toString(Charsets.UTF_8))
        val expected = SshSigning.encodePublicKey(pair)
        val actual = SshSigning.encodePublicKey(decoded)
        assertEquals("ssh-ed25519", actual.algorithmName)
        assertArrayEquals(expected.publicKeyBlob, actual.publicKeyBlob)
        val data = "Android device approval feasibility".toByteArray()
        val signature = SshSigning.sign("ssh-ed25519", encoded, null, data)
        val input = DataInputStream(ByteArrayInputStream(signature))
        assertEquals(11, input.readInt())
        assertEquals("ssh-ed25519", String(ByteArray(11).also(input::readFully)))
        assertEquals(64, input.readInt())
        val rawSignature = ByteArray(64).also(input::readFully)
        assertEquals(-1, input.read())
        val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider())
        verifier.initVerify(pair.public)
        verifier.update(data)
        assertTrue(verifier.verify(rawSignature))
        verifier.update("changed".toByteArray())
        assertFalse(verifier.verify(rawSignature))
        assertEquals("ssh-ed25519 " + Base64.getEncoder().encodeToString(expected.publicKeyBlob),
            actual.algorithmName + " " + Base64.getEncoder().encodeToString(actual.publicKeyBlob))
    }
    @Test fun roundTripStorePersistsPrivateKeyAndSurvivesSignOut() {
        val secrets = MemoryDeviceSecrets()
        val line = DeviceKeyStore(secrets).ensure()
        val privateKey = secrets.get(DeviceKeyStore.SECRET_ID)!!
        assertEquals(setOf("device_key"), secrets.values.keys)
        assertTrue(DeviceKeys.validKey(line))
        assertFalse(line.contains("PRIVATE"))
        val decoded = SshKeys.decodePemPrivateKey(privateKey.toString(Charsets.UTF_8))
        assertEquals(line, "ssh-ed25519 " + Base64.getEncoder().encodeToString(SshSigning.encodePublicKey(decoded).publicKeyBlob))
        secrets.put(AccountAuth.TOKENS, "signed-in".toByteArray())
        val auth = AccountAuth("app:callback", AccountHttp { _, _, _, _ -> error("No network expected") },
            { secrets.get(it)?.toString(Charsets.UTF_8) },
            { id, value -> if (value == null) secrets.remove(id) else secrets.put(id, value.toByteArray()) })
        auth.signOut()
        assertFalse(auth.hasSession())
        secrets.put(AccountAuth.TOKENS, "signed-in-again".toByteArray())
        assertEquals(line, DeviceKeyStore(secrets).ensure())
        assertArrayEquals(privateKey, secrets.get(DeviceKeyStore.SECRET_ID))
        assertEquals(1, secrets.writes.count { it == DeviceKeyStore.SECRET_ID })
    }

    @Test fun concurrentStoresGenerateOnlyOnce() {
        val secrets = MemoryDeviceSecrets()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val lines = pool.invokeAll((1..8).map { Callable { DeviceKeyStore(secrets).ensure() } }).map { it.get() }
            assertEquals(1, lines.distinct().size)
            assertEquals(listOf("device_key"), secrets.writes)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun presentInvalidKeyIsNeverReplaced() {
        val secrets = MemoryDeviceSecrets()
        val invalid = "not a private key".toByteArray()
        secrets.put(DeviceKeyStore.SECRET_ID, invalid)
        assertThrows(Exception::class.java) { DeviceKeyStore(secrets).ensure() }
        assertArrayEquals(invalid, secrets.get(DeviceKeyStore.SECRET_ID))
        assertEquals(listOf("device_key"), secrets.writes)
    }

    @Test fun writesPublicKeyForLiveProofWhenRequested() {
        val path = System.getenv("IGSH449_PUBKEY_OUT") ?: return
        val line = DeviceKeyStore(MemoryDeviceSecrets()).ensure()
        assertTrue(DeviceKeys.validKey(line))
        File(path).writeText(line + "\n")
    }
}

internal class MemoryDeviceSecrets : SecretStorage {
    val values = mutableMapOf<String, ByteArray>()
    val writes = mutableListOf<String>()
    override fun get(id: String): ByteArray? = values[id]?.copyOf()
    override fun put(id: String, value: ByteArray) { values[id] = value.copyOf(); writes.add(id) }
    override fun remove(id: String?) { values.remove(id) }
}
