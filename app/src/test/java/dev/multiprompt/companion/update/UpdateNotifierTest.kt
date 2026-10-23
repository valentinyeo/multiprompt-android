package dev.multiprompt.companion.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class UpdateNotifierTest {
    @Test fun boundedReadConsumesOnlyTheRequestedCharactersAndKeepsShortBodies() {
        var consumed = 0
        val reader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                if (consumed == 10 * 1024 * 1024) return -1
                val count = minOf(length, 10 * 1024 * 1024 - consumed)
                buffer.fill('x', offset, offset + count)
                consumed += count
                return count
            }

            override fun close() = Unit
        }
        assertEquals("x".repeat(65536), readBounded(reader, 65536))
        assertEquals(65536, consumed)
        assertEquals("short body", readBounded(StringReader("short body"), 65536))
    }
}
