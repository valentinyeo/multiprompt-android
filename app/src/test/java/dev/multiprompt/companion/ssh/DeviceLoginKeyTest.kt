package dev.multiprompt.companion.ssh

import android.app.Application
import dev.multiprompt.companion.account.DeviceKeyStore
import dev.multiprompt.companion.security.SecretStorage
import org.connectbot.sshlib.SshKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceLoginKeyTest {
    private class MemorySecrets : SecretStorage {
        val values = mutableMapOf<String, ByteArray>()
        override fun put(id: String, value: ByteArray) {
            values[id] = value
        }
        override fun get(id: String): ByteArray? = values[id]
        override fun remove(id: String?) {
            id?.let(values::remove)
        }
    }

    @Test fun aLostLoginKeyIsRebuiltInsteadOfBlockingEveryChat() {
        val secrets = MemorySecrets()
        val repository = SshRepository(secrets)
        assertNull(secrets.get(DeviceKeyStore.SECRET_ID))
        val key = repository.deviceLoginKey()
        assertNotNull("the app must rebuild its own login key", key)
        val text = key!!.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertNotNull(SshKeys.decodePemPrivateKey(text))
    }

    @Test fun AnExistingKeyIsKept() {
        val secrets = MemorySecrets()
        val repository = SshRepository(secrets)
        val first = repository.deviceLoginKey()
        val second = repository.deviceLoginKey()
        assertArrayEquals(first, second)
    }
}
