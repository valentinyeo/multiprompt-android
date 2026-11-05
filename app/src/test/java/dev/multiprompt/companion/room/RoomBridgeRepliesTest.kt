package dev.multiprompt.companion.room

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBridgeRepliesTest {
    @Test
    fun eachQuestionKeepsItsOwnReplyAndPrivateTextStaysOut() {
        val check = File("src/test/resources/rooms/bridge_replies_check.py")
        val assets = File("src/main/assets/rooms")
        assertTrue(check.isFile && assets.isDirectory)
        val process = ProcessBuilder("python3", "-I", check.absolutePath, assets.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        assertEquals("ok", output.trim())
    }
}
