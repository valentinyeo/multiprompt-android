package dev.multiprompt.companion.voice

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.TmuxText

data class VoiceReply(
    val text: String,
    val turnNumber: Int,
) {
    val key: String
        get() = "$turnNumber\u0000${text.replace(WHITESPACE, " ").trim()}"

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

object VoiceReplySelector {
    fun completedReply(output: String, agent: AgentKind, waitingForInput: Boolean): VoiceReply? {
        if (!waitingForInput) return null
        val blocks = TmuxText.readerBlocks(output, agent)
        val promptIndex = blocks.indexOfLast { it.kind == TmuxText.ReaderBlockKind.USER_PROMPT }
        if (promptIndex < 0) return null
        val text = blocks
            .drop(promptIndex + 1)
            .filter { it.kind == TmuxText.ReaderBlockKind.PROSE }
            .joinToString("\n\n") { block ->
                block.text.lineSequence()
                    .filterNot { line -> VOICE_STATUS_LINE.containsMatchIn(line.trim()) }
                    .joinToString("\n")
                    .trim()
            }
            .trim()
            .takeIf(String::isNotBlank)
            ?: return null
        return VoiceReply(
            text = text,
            turnNumber = blocks.count { it.kind == TmuxText.ReaderBlockKind.USER_PROMPT },
        )
    }

    private val VOICE_STATUS_LINE = Regex(
        "^(?:worked for |session complete$|task complete$|press up to edit queued messages$)",
        RegexOption.IGNORE_CASE,
    )
}
