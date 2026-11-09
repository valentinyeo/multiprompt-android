package dev.multiprompt.companion.ui

object LiveMention {
    private const val NAME_MAX = 192
    private const val SESSION_MAX = 64

    fun visible(text: String): String {
        if ('{' !in text || '@' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val marker = markerAt(text, i)
            if (marker == null) {
                out.append(text[i])
                i++
            } else {
                out.append(text, i, marker.first)
                i = marker.second
            }
        }
        return out.toString()
    }

    private fun space(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun sessionChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || c == ':' || c == '-'

    private fun markerAt(text: String, pos: Int): Pair<Int, Int>? {
        if (text[pos] != '@' || (pos > 0 && !space(text[pos - 1]))) return null
        var i = pos + 1
        if (i >= text.length || text[i] == '{' || space(text[i])) return null
        while (i < text.length && text[i] != '{' && text[i] != '\n' && text[i] != '\r' && i - pos <= NAME_MAX) i++
        if (i >= text.length || text[i] != '{' || space(text[i - 1])) return null
        var j = i + 1
        while (j < text.length && text[j] != '}' && j - i <= SESSION_MAX + 1) j++
        if (j >= text.length || text[j] != '}') return null
        val session = text.substring(i + 1, j)
        if (session.isEmpty() || session.length > SESSION_MAX || !session.all(::sessionChar)) return null
        return i to j + 1
    }
}
