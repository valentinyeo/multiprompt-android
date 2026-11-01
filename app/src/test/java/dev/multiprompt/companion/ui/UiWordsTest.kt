package dev.multiprompt.companion.ui

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiWordsTest {
    private val words: JSONObject by lazy {
        val file = File("../tools/ui-words/ui_words.json")
        assertTrue("shared word list is missing", file.exists())
        JSONObject(file.readText())
    }

    private fun values(): List<String> = words.keys().asSequence().map { words.getString(it) }.toList()

    @Test fun sharedListCameFromDesktop() {
        assertTrue("shared word list is too small to be the real one", words.length() > 100)
        assertTrue(values().contains("Archive Session"))
        assertTrue(values().contains("End tmux session"))
        assertTrue(values().any { it.contains("Reset this chat") })
    }

    @Test fun phoneLabelsAppearInTheSharedList() {
        val all = values()
        phoneLabels.forEach { label ->
            assertTrue("$label is not in the shared word list", all.any { it == label || it.endsWith(" $label") || it.contains(label) })
        }
    }

    @Test fun phoneMenusUseThoseLabels() {
        val source = File("src/main/java/dev/multiprompt/companion/ui/MultipromptApp.kt").readText()
        phoneLabels.forEach { label ->
            assertTrue("phone menus lost the shared label $label", source.contains("\"$label\"") || source.contains(label))
        }
    }

    private val phoneLabels = listOf(
        "Reset this chat",
        "Archive Session",
        "End tmux session",
        "Favorite",
        "Unfavorite",
        "Reply",
        "Copy",
        "Copy request ID",
        "Send again",
        "Remove",
        "Cancel message",
        "Release notes",
        "Screencast2 upload key",
        "Theme",
        "Auto",
    )
}
