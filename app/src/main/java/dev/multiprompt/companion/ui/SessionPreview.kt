package dev.multiprompt.companion.ui

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.TmuxText

internal object SessionPreview {
    private val boxChars = "│╭╮╰╯─━═┌┐└┘├┤┃║"
    private val promptGlyphs = charArrayOf('>', '❯', '›', '$', '#', '•', '●', '⏺', '·', '*')
    private val chrome = listOf(
        "for shortcuts", "to interrupt", "shift+tab", "ctrl+", "bypass permissions",
        "accept edits", "auto-compact", "esc to", "? for",
    )
    private val questionChrome = chrome + listOf(
        "to select", "to navigate", "to cancel", "space to toggle", "to submit", "ask codex", "ask claude",
    )
    private val activity = Regex("^[✢✳✶✻✽·]\\s+(.+?)(?:…|\\.\\.\\.)(?:\\s.*)?$")
    private val shellPrompt = Regex("^(?:\\S+@\\S+[: ].*?[#$]|[\\w./~:-]+[#$])(?:\\s.*)?$")
    private val completed = Regex("^[✢✳✶✻✽]\\s+.*\\bfor\\s+\\d+.*$")
    private val terminalReply = Regex("(?:\\^\\[|\u001b)\\[[?>=]?[0-9;]*[A-Za-z~]")
    private val toolCall = Regex("^[⏺●•]\\s+[A-Za-z_][\\w.-]*\\(.*$")
    private val listItem = Regex("^[-*+•·|]\\s")
    private val choiceItem = Regex("^\\d+[.)]\\s+\\S")
    private val questionMark = Regex("\\?\\s*$")
    private val userPrompt = Regex("^(?:User|You):", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("\\s+")

    private fun isReply(line: String): Boolean =
        (line.startsWith("⏺ ") || line.startsWith("● ") || line.startsWith("• ")) && !toolCall.matches(line)

    fun line(
        raw: String,
        agent: AgentKind = AgentKind.OTHER,
        replyWaiting: Boolean? = null,
    ): String {
        if (replyWaiting != false) askedQuestion(raw, agent)?.let { return collapseWhitespace(it) }
        return summaryFromScreen(contentAboveComposer(raw, agent), agent)
    }

    private fun askedQuestion(raw: String, agent: AgentKind): String? {
        val rows = contentAboveComposer(raw, agent).lines()
        val answeredAt = rows.indexOfLast { row ->
            val text = row.trim()
            isAgentLine(text) || toolCall.matches(text) || isUserText(text, agent)
        }
        val question = rows.drop(answeredAt + 1).lastOrNull { row ->
            row.isNotBlank() && !row.first().isWhitespace() && isQuestion(row, agent)
        }?.filterNot { it in boxChars }
        if (question != null) return question
        val reply = rows.getOrNull(answeredAt)?.trim() ?: return null
        if (!isReply(reply) || !TmuxText.isWaitingForInput(raw, agent)) return null
        return reply.trimStart(*promptGlyphs).trim()
            .split(Regex("(?<=[.!?])\\s+")).last().takeIf { isQuestion(it, agent) }
    }

    private fun isAgentLine(line: String): Boolean =
        isReply(line) || activity.matches(line)

    private fun isQuestion(row: String, agent: AgentKind): Boolean {
        val text = row.filterNot { it in boxChars }.trim()
        if (!text.any { it.isLetter() }) return false
        if (!questionMark.containsMatchIn(text)) return false
        if (text.startsWith("⎿") || toolCall.matches(text) || isReply(text)) return false
        if (listItem.containsMatchIn(text) || choiceItem.containsMatchIn(text.trimStart(*promptGlyphs).trim())) return false
        if (isUserText(text, agent) || shellPrompt.matches(text)) return false
        if (questionChrome.any { text.lowercase().contains(it) }) return false
        return TmuxText.runtimeDetails(text).model == null
    }

    private fun isUserText(line: String, agent: AgentKind): Boolean =
        !choiceItem.containsMatchIn(line.trimStart(*promptGlyphs).trim()) && when {
            line.startsWith("❯") -> true
            line.startsWith("›") -> agent != AgentKind.HAX
            line.startsWith("> ") -> true
            line.startsWith("▌") -> true
            else -> userPrompt.containsMatchIn(line)
        }

    private fun summaryFromScreen(raw: String, agent: AgentKind): String {
        val rows = raw.lines().map { it.trim() }
        val working = rows.lastOrNull { activity.matches(it) }
        val replyIndex = rows.indexOfLast { isReply(it) }
        if (working != null && rows.lastIndexOf(working) > replyIndex) {
            return "Working: ${activity.matchEntire(working)!!.groupValues[1]}"
        }
        val content = raw.lines().drop(replyIndex.coerceAtLeast(0)).takeWhile { row ->
            row.firstOrNull()?.isWhitespace() != false || !isQuestion(row, agent)
        }
        val useful = content.map { candidate ->
            terminalReply.replace(candidate, "").filterNot { it in boxChars }.trim()
        }.filter { candidate ->
            candidate.isNotEmpty() && chrome.none { candidate.lowercase().contains(it) } &&
                TmuxText.runtimeDetails(candidate).model == null && !shellPrompt.matches(candidate) &&
                !completed.matches(candidate) && candidate.any { it.isLetterOrDigit() } &&
                !candidate.startsWith("❯") && !candidate.startsWith("›") &&
                !candidate.startsWith("$ ") && !candidate.startsWith("# ")
        }
        val start = useful.indexOfLast { isReply(it) }
        if (start < 0) return ""
        val text = useful.drop(start).takeWhile { !it.startsWith("⎿") }.joinToString(" ")
        return collapseWhitespace(text.trimStart(*promptGlyphs))
            .split(Regex("(?<=[.!?])\\s+"), limit = 2).first()
    }

    private fun contentAboveComposer(raw: String, agent: AgentKind): String {
        val rows = raw.lines()
        val promptIndex = rows.indexOfLast { isUserText(it.filterNot { char -> char in boxChars }.trim(), agent) }
        val newestAgentLine = rows.indexOfLast { isAgentLine(it.trim()) || toolCall.matches(it.trim()) }
        return if (promptIndex > newestAgentLine) rows.take(promptIndex).joinToString("\n") else raw
    }

    private fun collapseWhitespace(text: String): String = text.trim().replace(whitespace, " ")
}
