package dev.multiprompt.companion.reader

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MentionCopyTest {
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
