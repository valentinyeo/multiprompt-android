package dev.multiprompt.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModeTest {
    private fun reply(key: String, text: String = "text $key") = ReadAloud.Spoken(key, text)

    private fun opened(vararg replies: ReadAloud.Spoken): VoiceMode.Memory =
        VoiceMode.observe(VoiceMode.Memory(), replies.toList(), ready = true, enabled = true, stable = true).memory

    @Test fun repliesOnScreenWhenChatOpensAreNeverRead() {
        val first = VoiceMode.observe(VoiceMode.Memory(), listOf(reply("b"), reply("a")), ready = true, enabled = true, stable = true)
        assertTrue(first.speak.isEmpty())
        val again = VoiceMode.observe(first.memory, listOf(reply("b"), reply("a")), ready = true, enabled = true, stable = true)
        assertTrue(again.speak.isEmpty())
    }

    @Test fun nothingHappensUntilTheChatIsReady() {
        val step = VoiceMode.observe(VoiceMode.Memory(), listOf(reply("a")), ready = false, enabled = true, stable = true)
        assertFalse(step.memory.baselined)
        assertTrue(step.speak.isEmpty())
    }

    @Test fun newReplyIsReadOnceAfterItSettles() {
        val memory = opened(reply("a"))
        val list = listOf(reply("b"), reply("a"))
        val early = VoiceMode.observe(memory, list, ready = true, enabled = true, stable = false)
        assertTrue(early.speak.isEmpty())
        val settled = VoiceMode.observe(early.memory, list, ready = true, enabled = true, stable = true)
        assertEquals(listOf(reply("b")), settled.speak)
        val repeat = VoiceMode.observe(settled.memory, list, ready = true, enabled = true, stable = true)
        assertTrue(repeat.speak.isEmpty())
    }

    @Test fun severalNewRepliesAreReadOldestFirst() {
        val memory = opened(reply("a"))
        val step = VoiceMode.observe(memory, listOf(reply("c"), reply("b"), reply("a")), ready = true, enabled = true, stable = true)
        assertEquals(listOf("b", "c"), step.speak.map { it.key })
    }

    @Test fun voiceModeOffReadsNothingAndLaterOnlyReadsRepliesAfterSwitchingOn() {
        val memory = opened(reply("a"))
        val whileOff = VoiceMode.observe(memory, listOf(reply("b"), reply("a")), ready = true, enabled = false, stable = true)
        assertTrue(whileOff.speak.isEmpty())
        val switchedOn = VoiceMode.observe(whileOff.memory, listOf(reply("b"), reply("a")), ready = true, enabled = true, stable = true)
        assertTrue(switchedOn.speak.isEmpty())
        val afterwards = VoiceMode.observe(switchedOn.memory, listOf(reply("c"), reply("b"), reply("a")), ready = true, enabled = true, stable = true)
        assertEquals(listOf("c"), afterwards.speak.map { it.key })
    }

    @Test fun olderHistoryLoadedLaterIsNotRead() {
        val memory = opened(reply("c"))
        val step = VoiceMode.observe(memory, listOf(reply("c"), reply("b"), reply("a")), ready = true, enabled = true, stable = true)
        assertTrue(step.speak.isEmpty())
        val next = VoiceMode.observe(step.memory, listOf(reply("d"), reply("c"), reply("b"), reply("a")), ready = true, enabled = true, stable = true)
        assertEquals(listOf("d"), next.speak.map { it.key })
    }

    @Test fun firstReplyInAnEmptyChatIsRead() {
        val memory = opened()
        val step = VoiceMode.observe(memory, listOf(reply("a")), ready = true, enabled = true, stable = true)
        assertEquals(listOf("a"), step.speak.map { it.key })
    }

    @Test fun sameTextUnderNewKeyIsNotReadTwice() {
        val memory = opened(reply("a", "all done"))
        val live = VoiceMode.observe(memory, listOf(reply("@live", "fixed it"), reply("a", "all done")), ready = true, enabled = true, stable = true)
        assertEquals(listOf("@live"), live.speak.map { it.key })
        val landed = VoiceMode.observe(live.memory, listOf(reply("native", "fixed it"), reply("a", "all done")), ready = true, enabled = true, stable = true)
        assertTrue(landed.speak.isEmpty())
    }

    @Test fun userMessagesAndToolOutputNeverReachTheQueue() {
        val messages = listOf(
            ChatHistoryMessage("u", "user", "please fix", "", 0, 0),
            ChatHistoryMessage("t", "assistant", "```\nls\n```", "", 0, 0),
            ChatHistoryMessage("p", "assistant", "still typing", "", 0, 0, pending = true),
            ChatHistoryMessage("a", "assistant", "fixed", "", 0, 0),
        )
        val replies = ReadAloud.replies(messages)
        val step = VoiceMode.observe(opened(), replies, ready = true, enabled = true, stable = true)
        assertEquals(listOf("fixed"), step.speak.map { it.text })
    }

    @Test fun playbackReportsWhenEverythingQueuedHasFinished() {
        val playback = VoiceMode.Playback()
        val first = playback.begin(false)
        val second = playback.begin(false)
        assertFalse(playback.end(first))
        assertTrue(playback.end(second))
    }

    @Test fun flushedPlaybackIgnoresLateStopOfOldReply() {
        val playback = VoiceMode.Playback()
        val old = playback.begin(false)
        val manual = playback.begin(true)
        assertFalse(playback.end(old))
        assertTrue(playback.end(manual))
    }
}
