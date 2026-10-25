package dev.multiprompt.companion.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {
    private fun pcm(amplitude: Int, samples: Int = 1600): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = if (i % 2 == 0) amplitude else -amplitude
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun silenceIsZero() = assertEquals(0f, AudioLevel.of(pcm(0), 3200), 0f)

    @Test fun loudSpeechIsNearOne() = assertTrue(AudioLevel.of(pcm(16000), 3200) > 0.9f)

    @Test fun quietRoomNoiseBarelyMoves() = assertTrue(AudioLevel.of(pcm(40), 3200) < 0.2f)

    @Test fun louderIsHigher() =
        assertTrue(AudioLevel.of(pcm(3000), 3200) > AudioLevel.of(pcm(300), 3200))

    @Test fun ignoresBytesPastTheCount() = assertEquals(0f, AudioLevel.of(pcm(16000), 0), 0f)

    @Test fun timerReadsMinutesAndSeconds() {
        assertEquals("0:00", AudioLevel.timer(0))
        assertEquals("0:03", AudioLevel.timer(3_400))
        assertEquals("1:05", AudioLevel.timer(65_000))
    }

    @Test fun newestLevelsLightTheRightmostDots() {
        assertEquals(listOf(false, false, false, true, false, true), AudioLevel.litDots(listOf(0.9f, 0f, 0.5f), 6))
        assertEquals(listOf(false, true), AudioLevel.litDots(listOf(0.9f, 0f, 0.5f), 2))
    }
}
