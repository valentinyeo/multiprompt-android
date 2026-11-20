package dev.multiprompt.companion.reader

data class SessionNameSpan(val start: Int, val end: Int, val session: String)

object ChatSessionLink {
    private val SESSION_ID = Regex("[A-Za-z0-9_.:-]{1,64}")

    private fun wordBreak(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun boundaryBefore(text: String, pos: Int) = pos == 0 || wordBreak(text[pos - 1])

    private fun boundaryAfter(text: String, pos: Int) = pos >= text.length || wordBreak(text[pos])

    private fun sessionId(value: String): Boolean {
        if (value.isEmpty() || value.length > 64 || !value.contains('-')) return false
        return SESSION_ID.matches(value)
    }

    fun spans(text: String, chats: List<MentionChat>): List<SessionNameSpan> {
        if (text.isEmpty() || chats.isEmpty()) return emptyList()
        val names = chats.map { it.name to it.session }
            .filter { (name, _) -> name.isNotEmpty() }
            .distinctBy { it.first }
            .sortedByDescending { it.first.length }
        val found = ArrayList<SessionNameSpan>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '`') {
                val ticks = text.substring(index).takeWhile { it == '`' }.length
                val close = text.indexOf("`".repeat(ticks), index + ticks).takeIf { it >= 0 } ?: text.length
                index = close + ticks
                continue
            }
            val brace = braceLinkAt(text, index)
            if (brace != null) {
                found += brace
                index = brace.end
                continue
            }
            val named = nameLinkAt(text, index, names)
            if (named != null) {
                found += named
                index = named.end
                continue
            }
            index++
        }
        return found
    }

    private fun braceLinkAt(text: String, pos: Int): SessionNameSpan? {
        if (pos >= text.length || !boundaryBefore(text, pos)) return null
        var at = pos
        if (text[at] == '@') {
            at++
            if (at >= text.length) return null
        }
        if (text[at] == '{' || wordBreak(text[at])) return null
        val nameStart = at
        var i = at
        while (i < text.length && i - pos <= 192) {
            when (val c = text[i]) {
                '\n', '\r' -> return null
                ' ', '\t' -> i++
                '{' -> break
                else -> i++
            }
        }
        if (i >= text.length || text[i] != '{' || i == nameStart || wordBreak(text[i - 1])) return null
        val close = text.indexOf('}', i + 1).takeIf { it > i && it - i <= 65 } ?: return null
        val target = text.substring(i + 1, close)
        if (!sessionId(target)) return null
        return SessionNameSpan(nameStart, i, target)
    }

    private fun nameLinkAt(text: String, pos: Int, names: List<Pair<String, String>>): SessionNameSpan? {
        if (!boundaryBefore(text, pos)) return null
        for ((name, session) in names) {
            if (!text.regionMatches(pos, name, 0, name.length, ignoreCase = false)) continue
            val after = pos + name.length
            if (!boundaryAfter(text, after)) continue
            return SessionNameSpan(pos, after, session)
        }
        return null
    }
}