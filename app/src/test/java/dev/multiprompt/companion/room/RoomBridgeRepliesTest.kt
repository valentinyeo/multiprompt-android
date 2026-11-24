package dev.multiprompt.companion.room

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBridgeRepliesTest {
    @Test
    fun eachQuestionKeepsItsOwnReplyPrivateTextStaysOutAndRoomsKeepPostOrder() {
        val assets = File("src/main/assets/rooms")
        assertTrue(assets.isDirectory)
        runCheck("src/test/resources/rooms/bridge_replies_check.py", assets)
        runCheck("src/test/resources/rooms/room_routing_check.py", assets)
    }

    private fun runCheck(path: String, assets: File) {
        val check = File(path)
        assertTrue("$path is missing", check.isFile)
        val process = ProcessBuilder("python3", "-I", check.absolutePath, assets.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("$path did not finish: $output", process.waitFor(120, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        assertTrue(output, output.contains("OK") || output.contains("ok"))
    }
}
