package dev.multiprompt.companion.reader

import dev.multiprompt.companion.ui.PromptComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MentionDecisionTest {
    private val design = MentionChat("SF Design Lead", "live-design")
    private val chats = listOf(design)

    private val rules = mapOf(
        "@SF Design Lead is the reply card built?" to "@SF Design Lead{live-design} is the reply card built?",
        "@SF Design Lead and @SF Design Lead check it" to "@SF Design Lead{live-design} and @SF Design Lead{live-design} check it",
        "please contact @SF Design Lead about it" to "please contact @SF Design Lead{live-design} about it",
        "thanks @SF Design Lead" to "thanks @SF Design Lead{live-design}",
        "FYI @SF Design Lead the build is red" to "FYI @SF Design Lead{live-design} the build is red",
        "cc @SF Design Lead the build is red" to "cc @SF Design Lead{live-design} the build is red",
        "not cc @SF Design Lead" to "not cc @SF Design Lead{live-design}",
        "ask @SF Design Lead" to "ask @SF Design Lead{live-design}",
    )

    private val note = "(This line is addressed to SF Design Lead and was already delivered to it. " +
        "Do not answer for SF Design Lead or relay it unless you are also asked.)"

    private val assets by lazy { sequenceOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory } }
    private val sources by lazy {
        sequenceOf(File("src/main/java/dev/multiprompt/companion"), File("app/src/main/java/dev/multiprompt/companion")).first { it.isDirectory }
    }

    private fun sent(draft: String): String =
        PromptComposer.composeMessage(ChatMention.annotate(draft, emptyList(), chats), emptyList(), null, emptyList())

    @Test fun androidSendsTheSameKindOfTextForEveryMentionRule() {
        for ((draft, marked) in rules) {
            val sent = sent(draft)
            assertEquals(draft, marked, sent)
            assertFalse("$draft carried a wire note", sent.contains("addressed to"))
            assertFalse("$draft carried a read-along note", sent.contains("read along"))
            assertEquals("$draft lost its words", draft, ChatMention.visible(sent))
        }
    }

    @Test fun theMentionNoteLivesInTheSharedDesktopScriptNotInAndroid() {
        val carriers = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("This line is addressed to") }.map { it.name }.sorted().toList()
        assertEquals(listOf("LegacyMessageIdentity.kt"), carriers)
        assertTrue(File(assets, "chat_history_reader.py").readText().contains("This line is addressed to"))
    }

    @Test fun oldHistoryRowsWithAMentionNoteStillMatchThePhoneCopy() {
        val typed = "/pstack:correct please contact @SF Design Lead{live-design} about it"
        assertTrue(MessageIdentity.same(MessageIdentity.text(typed), MessageIdentity.text(typed + "\n\n" + note)))
        assertTrue(MessageIdentity.same(MessageIdentity.text("@SF Design Lead{live-design} check this"),
            MessageIdentity.text("@SF Design Lead{live-design} check this")))
    }
}
