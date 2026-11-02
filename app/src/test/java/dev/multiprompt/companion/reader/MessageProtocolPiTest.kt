package dev.multiprompt.companion.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageProtocolPiTest {
    private val scope = MessageProtocol.Scope("pi", "abc-123", "pi-runner")

    @Test fun piIsAcceptedAsAProviderAndTravelsInTheRequest() {
        val request = JSONObject(MessageProtocol.request(4, scope, "message_submit", JSONObject().put("text", "hello")))
        assertEquals("pi", request.getString("provider"))
        assertEquals("abc-123", request.getString("sid"))
        assertEquals("pi-runner", request.getString("tmux"))
        assertEquals("message_submit", request.getString("op"))
        assertEquals(1, request.getInt("message_protocol"))
    }

    @Test fun anUnknownProviderIsStillRefused() {
        try {
            MessageProtocol.Scope("gemini", "abc-123", "pi-runner")
            throw AssertionError("unknown provider accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun piScopeRejectsAMismatchedRequestIdOrOperation() {
        try {
            MessageProtocol.request(0, scope, "message_submit")
            throw AssertionError("zero id accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            MessageProtocol.request(4, scope, "message_unknown")
            throw AssertionError("unknown operation accepted")
        } catch (_: IllegalArgumentException) { }
        assertTrue(MessageProtocol.request(9, scope, "message_status").contains("\"provider\":\"pi\""))
    }
}
