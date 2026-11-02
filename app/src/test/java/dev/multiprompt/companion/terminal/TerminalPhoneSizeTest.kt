package dev.multiprompt.companion.terminal

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.security.SecretStorage
import dev.multiprompt.companion.ssh.SshRepository
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.util.ReflectionHelpers

class TerminalPhoneSizeTest {
    private class MemorySecrets : SecretStorage {
        private val values = mutableMapOf<String, ByteArray>()
        override fun put(id: String, value: ByteArray) {
            values[id] = value
        }
        override fun get(id: String): ByteArray? = values[id]
        override fun remove(id: String?) {
            id?.let(values::remove)
        }
    }

    private val host = HostProfile("v", "Hetzner", "example.com", username = "valentin", keySecretId = "key")

    private fun connection(windowColumns: Int, windowRows: Int) = TerminalConnection(
        repository = SshRepository(MemorySecrets()),
        host = host,
        tmuxSessionName = "work",
        windowColumns = windowColumns,
        windowRows = windowRows,
    )

    @Test fun thePhoneShellIsNotPinnedToTheDesktopPaneSize() {
        val phone = connection(0, 0)
        assertNull(ReflectionHelpers.getField<Any?>(phone, "fixedSize"))
        phone.close()
    }

    @Test fun aFixedSizeStillExistsForCallersThatAskForOne() {
        val fixed = connection(200, 50)
        assertTrue("a requested size must still be honoured", ReflectionHelpers.getField<Any?>(fixed, "fixedSize") != null)
        fixed.close()
    }
}
