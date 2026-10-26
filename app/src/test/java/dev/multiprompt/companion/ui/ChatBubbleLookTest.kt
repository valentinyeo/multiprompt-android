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
