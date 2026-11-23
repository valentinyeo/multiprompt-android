package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiLoginPromptTest {
    private val open = "Open this link\nhttps://auth.example/device"
    private val confirm = "Logged in to anthropic. The next reply uses that login."

    @Test fun thePiLoginPromptIsFoundWithItsLink() {
        val prompt = PiLoginPrompt.detect(open)!!
        assertEquals("https://auth.example/device", prompt.link)
        assertEquals(open, prompt.text)
        assertEquals("https://auth.example/token?code=1&state=2", PiLoginPrompt.detect("Sign in at https://auth.example/token?code=1&state=2.")!!.link)
    }

    @Test fun ordinaryChatTextIsNotAPiLoginPrompt() {
        assertNull(PiLoginPrompt.detect("Done. The plan is written."))
        assertNull(PiLoginPrompt.detect("Read https://example.com/rules before you start."))
        assertNull(PiLoginPrompt.detect(""))
        assertNull(PiLoginPrompt.detect("x".repeat(PiLoginPrompt.MAX_TEXT + 1)))
    }

    @Test fun linksDropTrailingPunctuation() {
        assertEquals("https://auth.example/device", PiLoginPrompt.link("Sign in at (https://auth.example/device)."))
        assertEquals("https://auth.example/device", PiLoginPrompt.link("open this link: https://auth.example/device,"))
        assertNull(PiLoginPrompt.link("no link here"))
    }

    @Test fun aLinklessDialogAsksForTheCode() {
        val prompt = PiLoginPrompt.detect("Paste the code")!!
        assertNull(prompt.link)
        assertTrue(PiLoginPrompt.detect("Log in to a Pi provider:\nAnthropic (anthropic, not signed in)") != null)
    }

    @Test fun thePromptGoesStaleOncePiIsLoggedIn() {
        assertEquals(0, PiLoginPrompt.current(listOf(open, "hello"))!!.index)
        assertNull(PiLoginPrompt.current(listOf(open, confirm)))
        assertEquals(2, PiLoginPrompt.current(listOf(open, confirm, open))!!.index)
        assertNull(PiLoginPrompt.current(listOf(open, "Pi login failed")))
        assertEquals("anthropic", PiLoginPrompt.confirmsLogin(confirm))
        assertFalse(PiLoginPrompt.lapsed(confirm))
    }
}
