package dev.multiprompt.companion.security

import androidx.test.platform.app.InstrumentationRegistry
import dev.multiprompt.companion.account.DeviceKeyStore
import dev.multiprompt.companion.ssh.SshRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SecretStoreConcurrencyTest {
    @Test fun everyChatReadsTheSameLoginKeyWhileManyLoadAtOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = SecretStore(context)
        val second = SecretStore(context)
        val line = DeviceKeyStore(first).ensure()
        val stored = first.get(DeviceKeyStore.SECRET_ID)!!
        repeat(ROUNDS) { round ->
            val keys = runBlocking {
                (0 until READERS).map { index ->
                    async(Dispatchers.IO) {
                        val store = if (index % 2 == 0) first else second
                        if (index % 4 == 3) store.put("concurrency_probe_$index", byteArrayOf(round.toByte()))
                        SshRepository(store).deviceLoginKey()
                    }
                }.awaitAll()
            }
            keys.forEachIndexed { index, key -> assertArrayEquals("round $round reader $index lost the login key", stored, key) }
        }
        assertArrayEquals("the login key must never be replaced", stored, first.get(DeviceKeyStore.SECRET_ID))
        assertEquals(line, DeviceKeyStore(second).ensure())
    }

    private companion object {
        const val ROUNDS = 8
        const val READERS = 64
    }
}
