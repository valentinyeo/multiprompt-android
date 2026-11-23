package dev.multiprompt.companion.reader

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatReaderAssetTest {
    @Test fun bundledReaderMatchesDesktopSha() {
        val asset = sequenceOf(File("src/main/assets/chat_history_reader.py"), File("app/src/main/assets/chat_history_reader.py"))
            .first { it.isFile }
        val recorded = "a785edc351e56648306e7fd10f660323bcd6c3ef268a6f4318fb6de54edf5497"
        val hash = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(recorded, hash)
    }
}