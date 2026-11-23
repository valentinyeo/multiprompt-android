package dev.multiprompt.companion.reader

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class MpSendAssetTest {
    @Test fun bundledMpSendMatchesDesktopSha() {
        val asset = sequenceOf(File("src/main/assets/mp_send.py"), File("app/src/main/assets/mp_send.py")).first { it.isFile }
        val recorded = sequenceOf(File("src/main/assets/mp_send.sha"), File("app/src/main/assets/mp_send.sha"))
            .first { it.isFile }.readText().trim()
        val hash = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(recorded, hash)
    }
}