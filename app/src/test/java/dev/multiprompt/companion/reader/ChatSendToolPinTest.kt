package dev.multiprompt.companion.reader

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSendToolPinTest {
    private val root = sequenceOf(File("."), File("..")).map { it.absoluteFile.normalize() }
        .first { File(it, "tools/sync-chat-reader.sh").isFile }
    private val sync = File(root, "tools/sync-chat-reader.sh").readText()
    private val sendTool = File(root, "app/src/main/assets/mp_send.py")

    @Test fun bundledSendToolMatchesThePinnedDesktopBytes() {
        val pinned = Regex("'mp_send\\.py ([0-9a-f]{64})'").find(sync)
            ?.groupValues?.get(1)
            ?: throw AssertionError("tools/sync-chat-reader.sh pins no mp_send.py hash")
        val hash = MessageDigest.getInstance("SHA-256").digest(sendTool.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(
            "The bundled send tool must be the pinned desktop copy; run tools/sync-chat-reader.sh instead of editing it",
            pinned,
            hash,
        )
    }

    @Test fun bundledSendToolPublishesTheServerSendHelper() {
        val source = sendTool.readBytes().toString(Charsets.UTF_8)
        assertTrue(
            "The phone publishes this send tool over the server's newer one, so it must carry published_entries",
            source.contains("def published_entries("),
        )
    }

    @Test fun shippedReaderPinNamesTheSameDesktopRevisionAsTheSyncScript() {
        val revision = Regex("^revision=([0-9a-f]{40})$", RegexOption.MULTILINE).find(sync)
            ?.groupValues?.get(1)
            ?: throw AssertionError("tools/sync-chat-reader.sh pins no desktop revision")
        val shipped = File(root, "app/src/main/assets/chat_history_reader.sha").readText().trim()
        assertEquals("The shipped reader pin must name the desktop revision tools/sync-chat-reader.sh records", revision, shipped)
    }
}
