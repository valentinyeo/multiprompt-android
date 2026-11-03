package dev.multiprompt.companion.account

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.ByteBuffer

class BotPhotoTest {
    @get:Rule val folder = TemporaryFolder()

    private fun png(width: Int, height: Int, size: Int = 64): ByteArray {
        val header = ByteBuffer.allocate(33)
            .put(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)).putInt(13).put("IHDR".toByteArray())
            .putInt(width).putInt(height).put(byteArrayOf(8, 6, 0, 0, 0)).putInt(0).array()
        return header + ByteArray(maxOf(0, size - header.size))
    }

    private val tab = AccountTab("Chief", "Chief-Of-Staff", 0, "Other", photo = 2)
    private val myBotDigest = "7fb158a6819a5e497a67662c60c7837ffbd327627773fd876686c60f93d88d5a"
    private val chiefDigest = "44b6ce7ad143b0f7a96b98865d4055b049f45518e19e9b4753e8a2f6ee121b06"

    @Test fun keyMatchesTheDesktopDigest() {
        assertEquals(myBotDigest, BotPhotoKey.hex(1, "My-Bot"))
        assertEquals(BotPhotoKey.hex(1, "My-Bot"), BotPhotoKey.hex(1, "my-bot"))
        assertEquals(chiefDigest, BotPhotoKey.hex(0, "chief-of-staff"))
    }

    @Test fun onlySmallPngPicturesAreAccepted() {
        assertTrue(BotPhotoCache.validPng(png(256, 256)))
        assertTrue(BotPhotoCache.validPng(png(1, 1, 65536)))
        assertFalse(BotPhotoCache.validPng(png(257, 10)))
        assertFalse(BotPhotoCache.validPng(png(10, 0)))
        assertFalse(BotPhotoCache.validPng(png(10, 10, 65537)))
        assertFalse(BotPhotoCache.validPng("GIF89a not a png at all, padded out to length".toByteArray()))
        assertFalse(BotPhotoCache.validPng(ByteArray(0)))
    }

    @Test fun desktopPictureIsDownloadedOnceAndReplacedWhenItChanges() = runBlocking {
        val cache = BotPhotoCache(folder.root)
        val fetched = mutableListOf<String>()
        val picture = png(128, 128)
        assertTrue(cache.sync(listOf(tab)) { fetched.add(it); picture })
        assertArrayEquals(picture, cache.file(tab)!!.readBytes())
        assertFalse(cache.sync(listOf(tab)) { fetched.add(it); picture })
        assertEquals(listOf(chiefDigest), fetched)

        val changed = tab.copy(photo = 3)
        assertTrue(cache.sync(listOf(changed)) { png(64, 64) })
        assertNull(cache.file(tab))
        assertNotNull(cache.file(changed))
        assertEquals(1, folder.root.listFiles()!!.size)

        assertTrue(cache.sync(listOf(changed.copy(photo = null))) { fail("No picture to fetch"); null })
        assertNull(cache.file(changed))
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test fun aMissingBrokenOrUnreachablePictureNeverBreaksSync() = runBlocking {
        val cache = BotPhotoCache(folder.root)
        assertFalse(cache.sync(listOf(tab)) { null })
        assertFalse(cache.sync(listOf(tab)) { throw IOException("offline") })
        assertFalse(cache.sync(listOf(tab)) { png(512, 512) })
        assertNull(cache.file(tab))
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test fun pictureReplyDecodesBase64Png() {
        val picture = png(32, 32)
        val reply = JSONObject().put("png", java.util.Base64.getEncoder().encodeToString(picture))
        assertArrayEquals(picture, BotPhotoCache.decode(reply))
        assertNull(BotPhotoCache.decode(JSONObject().put("png", "%%%")))
        assertNull(BotPhotoCache.decode(JSONObject()))
    }
}
