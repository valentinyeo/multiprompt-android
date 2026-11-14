package dev.multiprompt.companion.model

import dev.multiprompt.companion.model.SlashCommand.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandTest {
    @Test fun planPermissionsAndModelOpenTheirPickers() {
        assertEquals(Route.PLAN, SlashCommand.route(true, "/plan "))
        assertEquals(Route.PERMISSIONS, SlashCommand.route(true, "/permissions"))
        assertEquals(Route.MODEL, SlashCommand.route(true, "/model"))
        assertEquals(Route.MODEL, SlashCommand.route(true, "  /model opus\n"))
    }

    @Test fun planAndPermissionsWithArgumentsAreUnavailable() {
        assertEquals(Route.UNAVAILABLE, SlashCommand.route(true, "/plan do the thing"))
        assertEquals(Route.UNAVAILABLE, SlashCommand.route(true, "/permissions all"))
    }

    @Test fun unsupportedTerminalCommandsAreUnavailable() {
        assertEquals(Route.UNAVAILABLE, SlashCommand.route(true, "/rewind"))
        assertEquals(Route.UNAVAILABLE, SlashCommand.route(true, "/resume old"))
        assertEquals(Route.UNAVAILABLE, SlashCommand.route(true, "/help\nmore"))
        assertEquals(Route.UNAVAILABLE, SlashCommand.route(true, "/workflows"))
    }

    @Test fun otherMessagesSendAsBefore() {
        assertEquals(Route.SEND, SlashCommand.route(true, "/planning"))
        assertEquals(Route.SEND, SlashCommand.route(true, "/custom-skill hello"))
        assertEquals(Route.SEND, SlashCommand.route(true, "/"))
        assertEquals(Route.SEND, SlashCommand.route(true, ""))
        assertEquals(Route.SEND, SlashCommand.route(true, "/home/x"))
        assertEquals(Route.SEND, SlashCommand.route(true, "look at /model please"))
        assertEquals(Route.SEND, SlashCommand.route(true, "run it, then /rewind"))
        assertEquals(Route.SEND, SlashCommand.route(true, "/Model"))
    }

    @Test fun nonLiveChatsAlwaysSend() {
        assertEquals(Route.SEND, SlashCommand.route(false, "/plan"))
        assertEquals(Route.SEND, SlashCommand.route(false, "/model"))
        assertEquals(Route.SEND, SlashCommand.route(false, "/rewind"))
    }

    @Test fun selectedSkillAlwaysSends() {
        assertEquals(Route.SEND, SlashCommand.decide(true, "/plan custom", true))
        assertEquals(Route.SEND, SlashCommand.decide(true, "/resume custom", true))
        assertEquals(Route.UNAVAILABLE, SlashCommand.decide(true, "/resume custom", false))
        assertEquals(Route.PLAN, SlashCommand.decide(true, "/plan", false))
    }

    @Test fun commandNameStopsAtFirstWhitespace() {
        assertEquals("model", SlashCommand.commandName("/model opus"))
        assertEquals("plan", SlashCommand.commandName("\n /plan\tx"))
        assertEquals("home/x", SlashCommand.commandName("/home/x"))
        assertNull(SlashCommand.commandName("hello /model"))
        assertNull(SlashCommand.commandName("/"))
    }
}
