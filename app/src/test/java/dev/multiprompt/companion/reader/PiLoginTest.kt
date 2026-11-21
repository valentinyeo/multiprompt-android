package dev.multiprompt.companion.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiLoginTest {
    private val sid = "6f353a80-d3df-4e44-88aa-edcc6c2b8999"

    @Test fun loginLineListsProvidersOrStartsAProvider() {
        val list = JSONObject(PiLogin.loginLine(9, sid, null))
        assertEquals(9, list.getInt("id"))
        assertEquals(PiLogin.OP, list.getString("op"))
        assertEquals("pi", list.getString("provider"))
        assertEquals(sid, list.getString("sid"))
        assertFalse(list.has("provider_id"))
        val login = JSONObject(PiLogin.loginLine(10, sid, "anthropic"))
        assertEquals("anthropic", login.getString("provider_id"))
    }

    @Test fun parseLoginSlashReadsTheFirstProviderToken() {
        assertNull(PiLogin.parseLoginSlash("hello"))
        assertNull(PiLogin.parseLoginSlash("/login"))
        assertEquals("anthropic", PiLogin.parseLoginSlash("/login anthropic"))
        assertEquals("openai-codex", PiLogin.parseLoginSlash("  /login openai-codex\nnext"))
    }

    @Test fun outcomeMatchesTheHostReply() {
        fun reply(status: String, reason: String? = null) = JSONObject().put("id", 5).put("message_protocol", 1)
            .put("result", JSONObject().put("status", status).apply { if (reason != null) put("reason", reason) })
            .toString()
        assertEquals(MessageActionResult.Applied, PiLogin.outcome(reply("ok"), 5))
        assertEquals(
            "Pi login: wait for this reply to finish",
            (PiLogin.outcome(reply("busy", "login_busy"), 5) as MessageActionResult.Rejected).reason,
        )
        assertEquals(
            "Pi login failed: this live chat is unavailable",
            (PiLogin.outcome(reply("refused", "invalid_provider"), 5) as MessageActionResult.Rejected).reason,
        )
        assertEquals(
            PiLogin.NO_ANSWER,
            (PiLogin.outcome("{}", 5) as MessageActionResult.Rejected).reason,
        )
    }

    @Test fun supportedRequiresAPiHostedSession() {
        assertTrue(PiLogin.supported("pi", sid))
        assertFalse(PiLogin.supported("claude", sid))
        assertFalse(PiLogin.supported("pi", ""))
    }
}