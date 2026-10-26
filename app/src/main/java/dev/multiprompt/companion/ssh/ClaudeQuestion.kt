package dev.multiprompt.companion.ssh

data class ClaudeQuestion(val title: String, val options: List<Option>) {
    data class Option(val index: Int, val label: String)

    companion object {
        private val option = Regex("^[❯›>]?[ \\t]*([1-9])[.)]\\s+(.+)$")
        private val footer = Regex("enter to select.*(?:navigate|esc to cancel)", RegexOption.IGNORE_CASE)

        fun parse(screen: String): ClaudeQuestion? {
            val lines = screen.lines().map(String::trim)
            val end = lines.indexOfLast { footer.containsMatchIn(it) }
            if (end < 0 || lines.drop(end + 1).any { it.isNotBlank() && !it.all { ch -> ch in "─━-" } }) return null
            val otherLine = (0 until end).lastOrNull { option.matchEntire(lines[it])?.groupValues?.get(2) == "Type something." } ?: return null
            var start = otherLine
            while (start > 0 && !(lines[start - 1].isNotEmpty() && lines[start - 1].all { it in "─━-" })) start--
            val rows = (start until end).mapNotNull { index -> option.matchEntire(lines[index])?.let { index to it } }
            if (rows.size < 3 || rows.map { it.second.groupValues[1].toInt() } != (1..rows.size).toList()) return null
            if (rows.none { lines[it.first].startsWith('❯') || lines[it.first].startsWith('›') || lines[it.first].startsWith('>') }) return null
            val other = rows.indexOfFirst { it.second.groupValues[2] == "Type something." }
            if (other < 2 || rows.any { it.second.groupValues[2].contains('☐') || it.second.groupValues[2].contains('☒') }) return null
            if (rows.take(other).none { lines[it.first].startsWith('❯') || lines[it.first].startsWith('›') || lines[it.first].startsWith('>') }) return null
            val title = lines.subList(start, rows.first().first).filter(String::isNotBlank).joinToString(" ")
            if (title.isBlank()) return null
            val options = rows.take(other).mapIndexed { index, (line, match) ->
                val detail = lines.subList(line + 1, rows[index + 1].first).filter { it.isNotBlank() && !it.all { ch -> ch in "─━-" } }
                Option(match.groupValues[1].toInt(), (listOf(match.groupValues[2]) + detail).joinToString(" "))
            }
            return ClaudeQuestion(title, options)
        }
    }
}
