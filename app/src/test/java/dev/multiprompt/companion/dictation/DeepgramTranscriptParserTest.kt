package dev.multiprompt.companion.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepgramTranscriptParserTest {
    @Test
    fun parsesFinalTranscript() {
        val message = """
            {
              "type": "Results",
              "is_final": true,
              "speech_final": true,
              "channel": {"alternatives": [{"transcript": "Send the prompt."}]}
            }
        """.trimIndent()

        assertEquals(
            DeepgramTranscript("Send the prompt.", isFinal = true, speechFinal = true),
            DeepgramTranscriptParser.parse(message),
        )
    }

    @Test
    fun ignoresMetadataAndBlankResults() {
        assertNull(DeepgramTranscriptParser.parse("""{"type":"Metadata"}"""))
        assertNull(
            DeepgramTranscriptParser.parse(
                """{"type":"Results","channel":{"alternatives":[{"transcript":""}]}}""",
            ),
        )
    }
}
