package dev.multiprompt.companion.upload

import android.app.Application
import dev.multiprompt.companion.security.SecretStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScreencastAccountCacheTest {
    private val storage = mutableMapOf<String, ByteArray>()
    private val secrets = object : SecretStorage {
        override fun put(id: String, value: ByteArray) { storage[id] = value }
        override fun get(id: String): ByteArray? = storage[id]
        override fun remove(id: String?) { storage.remove(id) }
    }
    private var account: String? = "first"
    private var remote: String? = "account-upload-key"
    private var fetches = 0
    private fun uploader() = ScreencastUploader(RuntimeEnvironment.getApplication(), secrets,
        { account }, { fetches++; remote })

    @Test fun accountKeyEnablesImageSelectionWithoutTypingAndSurvivesOfflineAndRestart() = runBlocking {
        val uploader = uploader()
        assertFalse(uploader.configured)
        assertTrue(uploader.prepare())
        assertTrue(uploader.configured)
        remote = null
        assertTrue(uploader().prepare())
        assertEquals(2, fetches)
        assertEquals(listOf("account-upload-key"), storage.values.map { it.toString(Charsets.UTF_8) })
    }

    @Test fun noAccountKeyLeavesTheManualSheetAvailable() = runBlocking {
        remote = null
        assertFalse(uploader().prepare())
        assertTrue(storage.isEmpty())
    }

    @Test fun accountCacheDoesNotReplaceTheSignedOutManualFallbackOrLeakToAnotherAccount() = runBlocking {
        val uploader = uploader()
        assertTrue(uploader.saveSecret("manual-upload-key"))
        assertTrue(uploader.prepare())
        assertEquals(setOf("manual-upload-key", "account-upload-key"), storage.values.map { it.toString(Charsets.UTF_8) }.toSet())
        remote = null
        account = "second"
        assertTrue(uploader.prepare())
        account = null
        assertTrue(uploader.prepare())
        assertEquals(2, fetches)
        secrets.remove("screencast2_upload_secret")
        assertFalse(uploader.prepare())
        account = "second"
        assertFalse(uploader.prepare())
        account = "first"
        assertTrue(uploader.prepare())
    }

    @Test fun switchingAccountsDuringFetchDoesNotCacheThePreviousAccountsKey() = runBlocking {
        val uploader = ScreencastUploader(RuntimeEnvironment.getApplication(), secrets,
            { account }, { account = "second"; "first-account-key" })
        assertFalse(uploader.prepare())
        assertTrue(storage.isEmpty())
    }
}
