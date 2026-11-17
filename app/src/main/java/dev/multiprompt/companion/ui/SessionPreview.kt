package dev.multiprompt.companion.ui

import dev.multiprompt.companion.ssh.TmuxText

internal object SessionPreview {
    private val boxChars = "│╭╮╰╯─━═┌┐└┘├┤┃║"
    private val promptGlyphs = charArrayOf('>', '❯', '›', '$', '#', '•', '●', '⏺', '·', '*')
    private val chrome = listOf(
        "for shortcuts", "to interrupt", "shift+tab", "ctrl+", "bypass permissions",
        "accept edits", "auto-compact", "esc to", "? for",
    )
    private val activity = Regex("^[✢✳✶✻✽·]\\s+(.+?)(?:…|\\.\\.\\.)(?:\\s.*)?$")
    private val shellPrompt = Regex("^(?:\\S+@\\S+[: ].*?[#$]|[\\w./~:-]+[#$])(?:\\s.*)?$")
    private val completed = Regex("^[✢✳✶✻✽]\\s+.*\\bfor\\s+\\d+.*$")
    private val terminalReply = Regex("(?:\\^\\[|\u001b)\\[[?>=]?[0-9;]*[A-Za-z~]")

    private val toolCall = Regex("^[⏺●•]\\s+[A-Za-z_][\\w.-]*\\(.*$")

    private fun isReply(line: String): Boolean =
        (line.startsWith("⏺ ") || line.startsWith("● ") || line.startsWith("• ")) && !toolCall.matches(line)

    fun line(raw: String): String {
        val rows = raw.lines().map { it.trim() }
        val working = rows.lastOrNull { activity.matches(it) }
        val replyIndex = rows.indexOfLast { it.startsWith("⏺ ") || it.startsWith("● ") || it.startsWith("• ") }
        if (working != null && rows.lastIndexOf(working) > replyIndex) {
            return "Working: ${activity.matchEntire(working)!!.groupValues[1]}"
        }
        val content = TmuxText.withoutActiveComposer(raw).lines()
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
        return text.trimStart(*promptGlyphs).trim().replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+"), limit = 2).first()
    }
}
