package dev.multiprompt.companion.ssh

object TmuxText {
    fun decodeHex(value: String): String {
        if (value.length % 2 != 0 || value.any { it.digitToIntOrNull(16) == null }) return ""
        val bytes = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Removes desktop TUI margins so captured output reads like a mobile transcript. */
    fun leftAligned(value: String): String = value
        .lineSequence()
        .joinToString("\n") { it.trimStart() }
        .trim()

    /** Hides the active terminal composer because Android provides its own native composer. */
    fun withoutActiveComposer(value: String): String {
        val lines = value.lines()
        val searchStart = (lines.size - COMPOSER_SEARCH_LINES).coerceAtLeast(0)
        val promptIndex = (lines.lastIndex downTo searchStart).firstOrNull { index ->
            val line = lines[index].trimStart()
            line.startsWith("❯") || line.startsWith("›")
        } ?: return value
        val dividerIndex = ((promptIndex - 1) downTo (promptIndex - 4).coerceAtLeast(searchStart))
            .lastOrNull { index -> isDivider(lines[index]) }
        return lines.take(dividerIndex ?: promptIndex).joinToString("\n").trimEnd()
    }

    private fun isDivider(value: String): Boolean {
        val compact = value.filterNot(Char::isWhitespace)
        return compact.length >= 8 && compact.all { it in DIVIDER_CHARACTERS }
    }

    private const val COMPOSER_SEARCH_LINES = 18
    private const val DIVIDER_CHARACTERS = "─━═╌╍-_▔▁"
}
