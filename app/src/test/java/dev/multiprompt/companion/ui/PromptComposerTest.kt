package dev.multiprompt.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptComposerTest {
    @Test fun nativeSkillStaysFirstWhenTheMessageHasImagesAndAQuote() {
        val skills = listOf(dev.multiprompt.companion.skills.NativeSkill("review", "Review"))
        assertEquals(
            "/review > Earlier reply\n\nhttps://example.com/photo.png\nmy task",
            PromptComposer.composeMessage("/review my task", listOf("https://example.com/photo.png"), "Earlier reply", skills),
        )
        assertEquals("/review", PromptComposer.composeMessage("/review ", emptyList(), null, skills))
        assertEquals(
            "> Earlier reply\n\n/reviewer my task",
            PromptComposer.composeMessage("/reviewer my task", emptyList(), "Earlier reply", skills),
        )
    }

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
                null,
                emptyList(),
            ),
        )
    }
}
