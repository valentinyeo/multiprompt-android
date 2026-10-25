package dev.multiprompt.companion.ui

internal object SessionPreview {
    private val boxChars = "│╭╮╰╯─━═┌┐└┘├┤┃║"
    private val promptGlyphs = charArrayOf('>', '❯', '›', '$', '#', '•', '●', '⏺', '·', '*')
    private val chrome = listOf(
        "for shortcuts",
        "to interrupt",
        "shift+tab",
        "ctrl+",
        "bypass permissions",
        "accept edits",
        "auto-compact",
        "esc to",
        "? for",
    )

    fun line(raw: String): String {
        for (candidate in raw.lineSequence().toList().asReversed()) {
            val cleaned = candidate
                .filterNot { it in boxChars }
                .trim()
                .trimStart(*promptGlyphs)
                .trim()
                .replace(Regex("\\s+"), " ")
            if (cleaned.isEmpty()) continue
            val lower = cleaned.lowercase()
            if (chrome.any { lower.contains(it) }) continue
            return cleaned
        }
        return ""
    }
}
