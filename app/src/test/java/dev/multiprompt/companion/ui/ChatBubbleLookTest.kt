package dev.multiprompt.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBubbleLookTest {
    @Test
    fun darkBubbleGetsLighterTopAndOutline() {
        val look = chatBubbleLook(Color(0xFF1B1B1B))
        assertEquals(Color(0xFF232323), look.top)
        assertEquals(Color(0xFF1B1B1B), look.bottom)
        assertEquals(Color(0xFF272727), look.outline)
    }

    @Test
    fun approvedReaderBubblesKeepExactGradientAndOutline() {
        assertEquals(ChatBubbleLook(Color(0xFF262626), Color(0xFF1E1E1E), Color(0xFF2E2E2E)), chatBubbleLook(ReaderCard))
        assertEquals(ChatBubbleLook(Color(0xFF414141), Color(0xFF3A3A3A), Color(0xFF4A4A4A)), chatBubbleLook(ReaderUserCard))
    }

    @Test
    fun lightBubbleGetsDarkerOutlineAndLighterTop() {
        val look = chatBubbleLook(Color(0xFFF4F4F4))
        assertTrue(look.outline.luminance() < look.bottom.luminance())
        assertTrue(look.top.luminance() >= look.bottom.luminance())
    }

    @Test
    fun agentAndUserBubblesBothDifferFromTheirOutline() {
        listOf(ChatBubbleUserDark, ChatBubbleAgentDark, ChatBubbleUserLight, ChatBubbleAgentLight).forEach {
            val look = chatBubbleLook(it)
            assertTrue(look.outline != look.bottom)
            assertTrue(look.top != look.bottom)
            assertEquals(it, look.bottom)
        }
    }
}
