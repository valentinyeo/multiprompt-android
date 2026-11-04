package dev.multiprompt.companion.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepgramKeyTest {
    private val sample = (0 until 40).joinToString("") { (it % 16).toString(16) }

    @Test
    fun keepsAPlainKey() {
        assertEquals(sample, DeepgramKey.normalize(sample))
    }

    @Test
    fun picksTheKeyOutOfPastedText() {
        assertEquals(sample, DeepgramKey.normalize("credential: $sample\nproject: abc"))
        assertEquals(sample, DeepgramKey.normalize("Token ${sample.uppercase()}"))
        assertEquals(sample, DeepgramKey.normalize("​$sample ‍"))
        assertEquals(sample, DeepgramKey.normalize("\"$sample\""))
    }

    @Test
    fun refusesValuesThatAreNotAKey() {
        assertNull(DeepgramKey.normalize(""))
        assertNull(DeepgramKey.normalize("   "))
        assertNull(DeepgramKey.normalize("a1b2c3d4e5"))
        assertNull(DeepgramKey.normalize("x".repeat(600)))
    }

    @Test
    fun keepsAnUnusualLongKeyWithoutSpaces() {
        val other = "dg_" + "Ab".repeat(20)
        assertEquals(other, DeepgramKey.normalize(" $other​ "))
    }

    @Test
    fun classifiesTheCheckAnswer() {
        assertEquals(DeepgramKey.Check.ACCEPTED, DeepgramKey.classify(400))
        assertEquals(DeepgramKey.Check.ACCEPTED, DeepgramKey.classify(200))
        assertEquals(DeepgramKey.Check.REJECTED, DeepgramKey.classify(401))
        assertEquals(DeepgramKey.Check.REJECTED, DeepgramKey.classify(403))
        assertEquals(DeepgramKey.Check.UNKNOWN, DeepgramKey.classify(null))
        assertEquals(DeepgramKey.Check.UNKNOWN, DeepgramKey.classify(503))
    }
}
