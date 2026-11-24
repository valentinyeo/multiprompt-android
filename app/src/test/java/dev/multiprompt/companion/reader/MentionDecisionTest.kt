package dev.multiprompt.companion.reader

import dev.multiprompt.companion.ui.PromptComposer
import org.json.JSONArray
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

    private val desktopCases: JSONArray by lazy {
        val fixture = sequenceOf(File("tools/fixtures/MUBO-247/cases.json"), File("../tools/fixtures/MUBO-247/cases.json")).first { it.isFile }
        JSONArray(fixture.readText())
    }

    private val marker = Regex("@([^@{}\n]+)\\{([A-Za-z0-9_.:-]+)}")

    private fun liveChats(text: String): List<MentionChat> = marker.findAll(text)
        .map { MentionChat(it.groupValues[1], it.groupValues[2], liveChat = true, hostProvider = "claude", hostSessionId = it.groupValues[2]) }
        .distinctBy { it.session }
        .toList()

    private fun sessions(text: String): List<String> = marker.findAll(text).map { it.groupValues[2] }.toList()

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    @Test fun everyDesktopMentionShapeGetsTheSameReadAlongCopy() {
        for (index in 0 until desktopCases.length()) {
            val case = desktopCases.getJSONObject(index)
            val text = case.getString("text")
            val expected = strings(case.getJSONArray("fyi"))
            val delivered = MentionCopy.deliveries(text, liveChats(text), "live-current").map { it.session }
            assertEquals("MUBO-247 case ${case.getString("id")} at src/chat_mention.zig:1139", expected, delivered)
            assertEquals(expected.distinct(), delivered)
            for (session in strings(case.getJSONArray("address"))) {
                assertFalse("MUBO-247 case ${case.getString("id")} addressed $session at src/chat_mention.zig:583", session in delivered)
            }
            assertTrue("MUBO-247 case ${case.getString("id")} lost its markers", sessions(text).isNotEmpty())
        }
    }

    @Test fun theCopyIsTheDesktopReadAlongNote() {
        val visible = "cc @Mention 2953 the build is red"
        val note = MentionCopy.note("verify-session", visible)
        val expected = "Valentin mentioned you in verify-session" +
            " (FYI only; read along, do not act unless asked). Context:\n\n" +
            visible
        assertEquals("src/chat_mention.zig:681 and :789 compose this note", expected, note)
        assertTrue(note.startsWith(MentionCopy.LEAD))
    }

    @Test fun onlyLiveChatsWithAHostIdentityReceiveTheCopy() {
        val text = "cc @Terminal{multiprompt-android} and @Mention 2953{live-cl_mention-2953}"
        val chats = listOf(
            MentionChat("Terminal", "multiprompt-android", liveChat = false, hostProvider = "claude", hostSessionId = "sid-terminal"),
            MentionChat("Mention 2953", "live-cl_mention-2953", liveChat = true, hostProvider = "claude", hostSessionId = "a1b2c3d4-1008-4000-8000-000000001008"),
            MentionChat("No identity", "live-cl_none", liveChat = true),
        )
        assertEquals(listOf("live-cl_mention-2953"), MentionCopy.deliveries(text, chats, "verify-session").map { it.session })
        assertEquals(emptyList<String>(), MentionCopy.deliveries("FYI @Mention 2953{live-cl_mention-2953} the build is red", chats, "live-cl_mention-2953").map { it.session })
    }

    @Test fun theCopyIsASharedMessageSubmitTheHostAlreadyUnderstands() {
        val scope = MessageProtocol.Scope("claude", "a1b2c3d4-1008-4000-8000-000000001008", "live-cl_mention-2953")
        val note = MentionCopy.note("verify-session", "cc @Mention 2953 the build is red")
        val request = MentionCopySend.request(7, scope, "fixturementioncopy0000000000001", note)
        assertTrue("src/chat_send.zig:696 queues the note as the target chat's own message", request.endsWith("\n"))
        assertTrue(request.contains("\"op\":\"message_submit\""))
        assertTrue(request.contains("\"message_protocol\":1"))
        assertTrue(request.contains("\"provider\":\"claude\""))
        assertTrue(request.contains("\"sid\":\"a1b2c3d4-1008-4000-8000-000000001008\""))
        assertTrue(request.contains("\"tmux\":\"live-cl_mention-2953\""))
        assertTrue(request.contains("FYI only; read along, do not act unless asked"))
    }

    @Test fun theCopyIdIsAnIdTheHostStoreAccepts() {
        val id = MentionCopySend.messageId()
        assertTrue(id, Regex("[A-Za-z0-9_-]{16,80}").matches(id))
        assertEquals("0123456789abcdef", MentionCopySend.messageId { "0123456789abcdef" })
        assertEquals("16", MentionCopySend.messageId { "" }.length.toString())
    }
}
