package dev.multiprompt.companion.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAssemblerTest {
    @Test fun answersSplitAcrossChunksJoinBackUp() {
        val assembler = LineAssembler()
        assertTrue(assembler.append("{\"ok\":".toByteArray()).isEmpty())
        assertEquals(listOf("{\"ok\":true}"), assembler.append("true}\n".toByteArray()))
    }

    @Test fun twoAnswersInOneChunkBothArrive() {
        val assembler = LineAssembler()
        assertEquals(listOf("a", "b"), assembler.append("a\nb\n".toByteArray()))
    }

    @Test fun windowsLineEndingsAreTolerated() {
        assertEquals(listOf("{\"ok\":true}"), LineAssembler().append("{\"ok\":true}\r\n".toByteArray()))
    }

    @Test fun anOversizedAnswerIsDroppedInsteadOfGrowingForever() {
        val assembler = LineAssembler(limit = 8)
        assertTrue(assembler.append("0123456789".toByteArray()).isEmpty())
        assertTrue(assembler.append("tail\n".toByteArray()).isEmpty())
        assertEquals(listOf("next"), assembler.append("next\n".toByteArray()))
    }
}
