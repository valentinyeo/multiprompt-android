package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SentDraftTest {
    private val draft = SentDraft("fix the build", listOf("https://example.com/a.png"), null, "quoted")

    @Test
    fun failedSendPutsTheTextBackInAnEmptyBox() {
        assertEquals("fix the build", draft.restoredPrompt(""))
        assertEquals("fix the build", draft.restoredPrompt("   "))
    }

    @Test
    fun failedSendKeepsWhatWasTypedSinceAfterTheRestoredText() {
        assertEquals("fix the build\nand the tests", draft.restoredPrompt("and the tests"))
    }

    @Test
    fun failedSendWithNoTextKeepsTheCurrentBox() {
        assertEquals("typed later", SentDraft("", emptyList(), null, null).restoredPrompt("typed later"))
    }

    @Test
    fun failedSendRestoresImagesWithoutDuplicates() {
        assertEquals(
            listOf("https://example.com/a.png", "https://example.com/b.png"),
            draft.restoredImages(listOf("https://example.com/a.png", "https://example.com/b.png")),
        )
        assertEquals(listOf("https://example.com/a.png"), draft.restoredImages(emptyList()))
    }
}
