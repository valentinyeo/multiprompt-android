package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiLoginPromptTest {
    private val open = "Open this link\nhttps://auth.example/device"
    private val confirm = "Logged in to anthropic. The next reply uses that login."

    private fun reply(text: String) = ChatHistoryMessage("assistant-key", "assistant", text, "", 0, 0, "history-1")

    private fun said(text: String) = ChatHistoryMessage("user-key", "user", text, "", 0, 0, "message-1")

    @Test fun thePiLoginPromptIsFoundWithItsLink() {
        val prompt = PiLoginPrompt.prompt(reply(open))!!
        assertEquals("https://auth.example/device", prompt.link)
        assertEquals(open, prompt.text)
        assertEquals(
            "https://auth.example/token?code=1&state=2",
            PiLoginPrompt.prompt(reply("Sign in at https://auth.example/token?code=1&state=2."))!!.link,
        )
    }

    @Test fun aPromptThatIsOnlyALinkStillShowsTheCard() {
        val prompt = PiLoginPrompt.prompt(reply("https://auth.example/device"))!!
        assertEquals("https://auth.example/device", prompt.link)
        assertEquals("https://auth.example/device", prompt.text)
    }

    @Test fun theUsersOwnMessageAboutLoginIsNotAPrompt() {
        assertNull(PiLoginPrompt.prompt(said("fix the login bug")))
        assertNull(PiLoginPrompt.prompt(said("sign in to pi and try again")))
        assertNull(PiLoginPrompt.prompt(said("https://auth.example/device")))
        assertNull(PiLoginPrompt.current(listOf(said("fix the login bug"), said("sign in to pi for me"))))
    }

    @Test fun aUserMessageNeverClearsALivePrompt() {
        assertNotNull(PiLoginPrompt.current(listOf(reply(open), said(confirm))))
        assertNull(PiLoginPrompt.current(listOf(reply(open), reply(confirm))))
    }

    @Test fun ordinaryChatTextIsNotAPiLoginPrompt() {
        assertNull(PiLoginPrompt.prompt(reply("Done. The plan is written.")))
        assertNull(PiLoginPrompt.prompt(reply("I fixed the login bug and the sign-in tests pass.")))
        assertNull(PiLoginPrompt.prompt(reply("Read https://example.com/rules before you start.")))
        assertNull(PiLoginPrompt.prompt(reply("Done. The plan is written.\nhttps://example.com/rules")))
        assertNull(PiLoginPrompt.prompt(reply("")))
        assertNull(PiLoginPrompt.prompt(reply("x".repeat(PiLoginPrompt.MAX_TEXT + 1))))
    }

    @Test fun linksAreHttpsOnly() {
        assertNull(PiLoginPrompt.link("open this link: http://auth.example/device"))
        assertNull(PiLoginPrompt.prompt(reply("Open this link\nhttp://auth.example/device")))
        assertEquals("https://auth.example/device", PiLoginPrompt.link("Sign in at (https://auth.example/device)."))
        assertEquals("https://auth.example/device", PiLoginPrompt.link("open this link: https://auth.example/device,"))
        assertNull(PiLoginPrompt.link("no link here"))
    }

    @Test fun aLinklessDialogAsksForTheCode() {
        val prompt = PiLoginPrompt.prompt(reply("Paste the code"))!!
        assertNull(prompt.link)
        assertNotNull(PiLoginPrompt.prompt(reply("Pi needs an answer.\nEnter the code")))
        assertNotNull(PiLoginPrompt.prompt(reply("Log in to a Pi provider:\nAnthropic (anthropic, not signed in)")))
    }

    @Test fun thePromptGoesStaleOncePiIsLoggedIn() {
        assertEquals(0, PiLoginPrompt.current(listOf(reply(open), reply("hello")))!!.index)
        assertNull(PiLoginPrompt.current(listOf(reply(open), reply(confirm))))
        assertEquals(2, PiLoginPrompt.current(listOf(reply(open), reply(confirm), reply(open)))!!.index)
        assertNull(PiLoginPrompt.current(listOf(reply(open), reply("Pi login failed"))))
        assertEquals("anthropic", PiLoginPrompt.confirmsLogin(confirm))
        assertFalse(PiLoginPrompt.lapsed(confirm))
    }
}
