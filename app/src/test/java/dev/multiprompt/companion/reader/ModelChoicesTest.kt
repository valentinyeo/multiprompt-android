package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChoicesTest {
    @Test
    fun claudeSettingsOfferEveryStructuredModel() {
        val choices = ModelChoices.of(AgentKind.CLAUDE, HostSettings("sonnet", "high", "plan", true))

        assertEquals(listOf("Opus", "Sonnet", "Haiku", "Fable"), choices.map { it.label })
        assertEquals(listOf(false, true, false, false), choices.map { it.current })
        assertTrue(choices.all { it.command == null })
    }

    @Test
    fun aChatWithoutHostSettingsOffersModelsThroughRuntimeCommands() {
        val choices = ModelChoices.of(AgentKind.CLAUDE, null)

        assertEquals(listOf("/model opus", "/model sonnet", "/model haiku", "/model fable"), choices.map { it.command })
        assertTrue(choices.none { it.current })
    }

    @Test
    fun aPendingPickShowsAsTheCurrentModel() {
        val choices = ModelChoices.of(AgentKind.CLAUDE, HostSettings("opus", "", "", true), pendingFamily = "sonnet")

        assertEquals(listOf("Sonnet"), choices.filter { it.current }.map { it.label })
    }

    @Test
    fun codexChoicesNeedHostSettingsAndCarryNoShellCommand() {
        assertTrue(ModelChoices.of(AgentKind.CODEX, null).isEmpty())

        val choices = ModelChoices.of(AgentKind.CODEX, HostSettings("sol", "", "", true))

        assertEquals(listOf("Sol", "Terra", "Luna", "Astra"), choices.map { it.label })
        assertEquals(listOf("Sol"), choices.filter { it.current }.map { it.label })
        assertNull(choices.first().command)
    }

    @Test
    fun anAgentWithoutModelSettingsOffersNothing() {
        assertTrue(ModelChoices.of(AgentKind.PI, HostSettings("opus", "", "", true)).isEmpty())
    }
}
