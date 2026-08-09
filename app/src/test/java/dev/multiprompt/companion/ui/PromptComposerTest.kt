package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptComposerTest {
    @Test
    fun imageUrlEndsWithASeparator() {
        assertEquals(
            "Please inspect this image\nhttps://screencast2.com/example.png ",
            PromptComposer.appendImageUrl(
                "Please inspect this image",
                "https://screencast2.com/example.png",
            ),
        )
    }

    @Test
    fun dictationAddsExactlyOneSeparatorAfterImageUrl() {
        assertEquals(
            "https://screencast2.com/example.png describe the layout",
            PromptComposer.appendDictation(
                "https://screencast2.com/example.png ",
                "describe the layout",
            ),
        )
    }

    @Test
    fun imageUrlsCanBeAddedAsSeparateLines() {
        assertEquals(
            "https://screencast2.com/one.png\nhttps://screencast2.com/two.png ",
            PromptComposer.appendImageUrls(
                "",
                listOf(
                    "https://screencast2.com/one.png",
                    "https://screencast2.com/two.png",
                ),
            ),
        )
    }

    @Test
    fun attachmentsAreKeptOutsideTheVisibleMessageTextUntilSend() {
        assertEquals(
            "https://screencast2.com/example.png\nDescribe the screenshot",
            PromptComposer.composeMessage(
                "Describe the screenshot",
                listOf("https://screencast2.com/example.png"),
            ),
        )
    }
}
