package dev.multiprompt.companion.update

object ReleaseNoteLines {
    const val PREVIEW_LINES = 3

    private val bullet = Regex("""^\s*(?:[-*•+]\s+|\d+[.)]\s+)""")
    private val heading = Regex("""^#{1,6}\s*""")
    private val htmlTag = Regex("""<[^>]*>""")
    private val emphasis = Regex("""\*\*|__|`|~~""")
    private val whitespace = Regex("""\s+""")
    private val ticketOnly = Regex("""(?:IGSH|HYCI|MUBO)-\d+""")
    private val headers = setOf("what's new", "whats new", "release notes", "release history", "changes", "changelog")

    fun changes(notes: String): List<String> = notes.lineSequence()
        .map(::plainLine)
        .filter(String::isNotEmpty)
        .filterNot { it.lowercase() in headers }
        .distinct()
        .toList()

    fun preview(notes: String): List<String> = changes(notes).take(PREVIEW_LINES)

    private fun plainLine(raw: String): String {
        val line = raw
            .replace(htmlTag, " ")
            .replace(bullet, "")
            .replace(heading, "")
            .replace(emphasis, "")
            .replace(whitespace, " ")
            .trim()
        return if (ticketOnly.matches(line)) "" else line
    }
}
