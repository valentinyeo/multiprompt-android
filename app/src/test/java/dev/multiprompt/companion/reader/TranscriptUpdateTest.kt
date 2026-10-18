package dev.multiprompt.companion.reader

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TranscriptUpdateTest {
    private val current = listOf(TranscriptMessage("assistant", null, "Existing reply"))
    private val next = listOf(TranscriptMessage("assistant", null, "New reply"))

    private fun response(status: String) = TranscriptProtocol.Response(status, "session", "1.2", next)

    @Test fun transportFailureRetainsCurrentTranscript() {
        assertSame(current, transcriptAfterUpdate(current, null))
        assertNull(transcriptAfterUpdate(null, null))
    }

    @Test fun sameStatusRetainsCurrentTranscript() {
        assertSame(current, transcriptAfterUpdate(current, response("same")))
        assertNull(transcriptAfterUpdate(null, response("same")))
    }

    @Test fun okStatusReplacesCurrentTranscript() {
        assertSame(next, transcriptAfterUpdate(current, response("ok")))
        assertSame(next, transcriptAfterUpdate(null, response("ok")))
    }

    @Test fun explicitMissingOrUnexpectedStatusClearsCurrentTranscript() {
        listOf("missing", "error", "").forEach { status ->
            assertNull(transcriptAfterUpdate(current, response(status)))
        }
    }

    @Test fun sameAfterTransportFailureStillRetainsTranscriptUntilExplicitMissing() {
        val retained = transcriptAfterUpdate(current, null)
        val unchanged = transcriptAfterUpdate(retained, response("same"))
        assertSame(current, unchanged)
        assertNull(transcriptAfterUpdate(unchanged, response("missing")))
    }
}
