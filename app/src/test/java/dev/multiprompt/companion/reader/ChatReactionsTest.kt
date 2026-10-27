package dev.multiprompt.companion.reader

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReactionsTest {
    private fun message(id: String, text: String, role: String = "assistant") =
        ChatHistoryMessage(id, role, text, "", 0, 1, id)

    @Test
    fun bothAppsUseTheSameQuotedWireFormat() {
        assertEquals("a430d84680aabd0b", ChatReactions.reference("hello"))
        val fixture = javaClass.getResourceAsStream("/reaction-fixture.json")!!.bufferedReader().use { it.readText() }
        val cases = JSONArray(fixture)
        for (index in 0 until cases.length()) {
            val row = cases.getJSONObject(index)
            val emoji = ChatReactions.Emoji.entries.single { it.text == row.getString("emoji") }
            val actual = ChatReactions.format(emoji, row.getString("id"), row.getString("text"))
            assertEquals(row.getString("expected"), actual)
            assertEquals(emoji, ChatReactions.parse(actual!!)!!.emoji)
        }
        assertNull(ChatReactions.parse("👍 to: Same first words"))
        assertNull(ChatReactions.parse("ordinary message"))
        assertNull(ChatReactions.format(ChatReactions.Emoji.LIKE, "id", " \n "))
    }

    @Test
    fun repeatedPrefixesKeepTheSameTargetAfterRestartAndTranscriptConfirmation() {
        val first = message("msg_first", "Same first words")
        val second = message("msg_second", "Same first words")
        val line = ChatReactions.format(ChatReactions.Emoji.LIKE, second.requestId, second.text)!!
        val pending = message("pending", line, "user").copy(pending = true)
        val waiting = ChatReactions.present(listOf(first, second, pending))
        assertEquals(listOf(first, second), waiting.messages)
        assertFalse(waiting.badges.containsKey(first.key))
        assertEquals(setOf(ChatReactions.Emoji.LIKE), waiting.badges[second.key])
        val raw = listOf(first, second, message("reaction", line, "user"))
        val confirmed = ChatReactions.present(raw)
        assertEquals(listOf(first, second), confirmed.messages)
        assertEquals(waiting.badges, confirmed.badges)
        val restarted = ChatReactions.present(raw.map { it.copy() })
        assertEquals(confirmed.messages, restarted.messages)
        assertEquals(confirmed.badges, restarted.badges)
    }

    @Test
    fun unmatchedWrongQuotedFutureAmbiguousAndFailedReactionsNeverDisappear() {
        val reply = message("msg_first", "Same first words")
        val unknown = ChatReactions.format(ChatReactions.Emoji.HEART, "not-loaded", reply.text)!!
        val wrong = ChatReactions.format(ChatReactions.Emoji.DISLIKE, reply.requestId, "Wrong quote")!!
        for (line in listOf(unknown, wrong, "ordinary message", "👍 to: Same first words")) {
            val raw = listOf(reply, message("user", line, "user"))
            val shown = ChatReactions.present(raw)
            assertEquals(raw, shown.messages)
            assertTrue(shown.badges.isEmpty())
            val pending = raw.dropLast(1) + raw.last().copy(pending = true)
            assertEquals(pending, ChatReactions.present(pending).messages)
        }
        val line = ChatReactions.format(ChatReactions.Emoji.LIKE, reply.requestId, reply.text)!!
        val before = listOf(message("user", line, "user"), reply)
        assertEquals(before, ChatReactions.present(before).messages)
        val ambiguous = listOf(reply, reply.copy(key = "other"), message("user", line, "user"))
        assertEquals(ambiguous, ChatReactions.present(ambiguous).messages)
        val pending = listOf(reply, message("pending", line, "user").copy(pending = true))
        val failed = ChatReactions.present(pending, collapsePending = false)
        assertEquals(pending, failed.messages)
        assertTrue(failed.badges.isEmpty())
    }

    @Test
    fun mixedReactionLinesHideOnlyTheWireAndPreserveUserWordsAndReceipt() {
        val reply = message("msg_reply", "Synthetic answer for a reaction")
        val line = ChatReactions.format(ChatReactions.Emoji.LIKE, reply.requestId, reply.text)!!
        val words = "My synthetic follow-up stays here"
        for (text in listOf("$words\n\n$line", "$line\n\n$words", "$words\n$line\nMore words")) {
            val user = message("mixed", text, "user")
            val raw = listOf(reply, user)
            val shown = ChatReactions.present(raw)
            assertEquals(text.lines().filterNot { it == line }.joinToString("\n").trim('\n'), shown.messages.last().text)
            assertEquals(user.copy(text = shown.messages.last().text), shown.messages.last())
            assertEquals(setOf(ChatReactions.Emoji.LIKE), shown.badges[reply.key])
            assertEquals(text, raw.last().text)
            assertEquals(text, raw.last().transcript().text)
        }
    }

    @Test
    fun onlyReactionLinesLeaveNoBubbleEvenWithBlankLinesOrPendingEcho() {
        val reply = message("msg_reply", "Synthetic answer for a reaction")
        val like = ChatReactions.format(ChatReactions.Emoji.LIKE, reply.requestId, reply.text)!!
        val heart = ChatReactions.format(ChatReactions.Emoji.HEART, reply.requestId, reply.text)!!
        for (pending in listOf(false, true)) {
            val user = message("reactions", "\n$like\n\n$heart\n", "user").copy(pending = pending)
            val shown = ChatReactions.present(listOf(reply, user))
            assertEquals(listOf(reply), shown.messages)
            assertEquals(setOf(ChatReactions.Emoji.LIKE, ChatReactions.Emoji.HEART), shown.badges[reply.key])
            assertEquals(listOf(reply, user), ChatReactions.present(listOf(reply, user.copy(pending = true)), collapsePending = false).messages.map { if (it.key == user.key) it.copy(pending = pending) else it })
        }
    }

    @Test
    fun mixedInvalidReactionLinesAndAssistantTextRemainUntouched() {
        val reply = message("msg_reply", "Synthetic answer for a reaction")
        val valid = ChatReactions.format(ChatReactions.Emoji.LIKE, reply.requestId, reply.text)!!
        val wrong = ChatReactions.format(ChatReactions.Emoji.LIKE, reply.requestId, "Wrong quote")!!
        val unknown = ChatReactions.format(ChatReactions.Emoji.LIKE, "unloaded", reply.text)!!
        for (text in listOf("My words\n$wrong", "My words\n$unknown", "My words\n👍 to: plain text", "My words\n$valid extra", "My words\n[mp:ordinary]")) {
            val raw = listOf(reply, message("user", text, "user"))
            assertEquals(raw, ChatReactions.present(raw).messages)
            assertTrue(ChatReactions.present(raw).badges.isEmpty())
        }
        val raw = listOf(reply, message("assistant", "An example\n$valid"))
        assertEquals(raw, ChatReactions.present(raw).messages)
    }

    @Test
    fun quotesStayShortWithoutSplittingAnEmoji() {
        val line = ChatReactions.format(ChatReactions.Emoji.HEART, "id", "a".repeat(79) + "🙂 later")!!
        val quote = ChatReactions.parse(line)!!.quote
        assertEquals("a".repeat(79), quote)
    }

    @Test
    fun aLoneSessionReplyToAReactionIsHiddenAndOtherRepliesStay() {
        val target = message("msg_target", "Everything is done")
        val line = ChatReactions.format(ChatReactions.Emoji.LIKE, target.requestId, target.text)!!
        val reaction = message("reaction", line, "user")
        val ack = message("ack", "Noted, thanks.")
        val shown = ChatReactions.present(listOf(target, reaction, ack))
        assertEquals(listOf(target), shown.messages)
        assertEquals(setOf(ChatReactions.Emoji.LIKE), shown.badges[target.key])
        val ordinary = message("ordinary", "Please continue", "user")
        val kept = listOf(target, ordinary, ack)
        assertEquals(kept, ChatReactions.present(kept).messages)
        val work = listOf(target, reaction, ack, message("report", "Finished the follow-up"))
        assertEquals(listOf(target, ack, work.last()), ChatReactions.present(work).messages)
        val next = message("next", "Thanks, what is next?", "user")
        val later = message("answer", "Next up is the Settings page")
        assertEquals(listOf(target, next, later), ChatReactions.present(listOf(target, reaction, ack, next, later)).messages)
    }
}
