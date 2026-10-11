package dev.multiprompt.companion.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramEndpointTest {
    @Test
    fun triesEuFirstThenGlobal() {
        assertTrue(DeepgramEndpoint.url(0).startsWith("wss://api.eu.deepgram.com/v1/listen?"))
        assertTrue(DeepgramEndpoint.url(1).startsWith("wss://api.deepgram.com/v1/listen?"))
    }

    @Test
    fun fallsBackOnlyBeforeOpenAndNotForBadKey() {
        assertTrue(DeepgramEndpoint.shouldTryNextHost(0, null, opened = false, stopping = false))
        assertTrue(DeepgramEndpoint.shouldTryNextHost(0, 404, opened = false, stopping = false))
        assertFalse(DeepgramEndpoint.shouldTryNextHost(0, 401, opened = false, stopping = false))
        assertFalse(DeepgramEndpoint.shouldTryNextHost(0, 403, opened = false, stopping = false))
        assertFalse(DeepgramEndpoint.shouldTryNextHost(0, null, opened = true, stopping = false))
        assertFalse(DeepgramEndpoint.shouldTryNextHost(0, null, opened = false, stopping = true))
        assertFalse(DeepgramEndpoint.shouldTryNextHost(1, null, opened = false, stopping = false))
    }

    @Test
    fun failureMessagesNameTheCause() {
        assertEquals("Deepgram rejected the API key", DeepgramEndpoint.failureMessage(401, null))
        assertEquals("Deepgram account has no credit left", DeepgramEndpoint.failureMessage(402, null))
        assertTrue(DeepgramEndpoint.failureMessage(500, null).contains("HTTP 500"))
        assertTrue(
            DeepgramEndpoint.failureMessage(null, java.net.UnknownHostException("x")).contains("find Deepgram"),
        )
        assertEquals("Dictation connection failed", DeepgramEndpoint.failureMessage(null, null))
    }
}
