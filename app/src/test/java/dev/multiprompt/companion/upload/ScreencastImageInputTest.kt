package dev.multiprompt.companion.upload

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreencastImageInputTest {
    @Test
    fun readsASelectedImageStreamOnce() {
        val image = ByteArray(128) { it.toByte() }

        assertArrayEquals(image, ByteArrayInputStream(image).readBounded(256))
    }

    @Test
    fun rejectsSourcesAboveTheMemoryLimit() {
        val image = ByteArray(257)

        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(image).readBounded(256)
        }
    }
}
