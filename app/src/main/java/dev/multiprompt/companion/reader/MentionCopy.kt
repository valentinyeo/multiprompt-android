package dev.multiprompt.companion.reader

data class MentionCopyTarget(
    val name: String,
    val session: String,
    val provider: String,
    val hostSessionId: String,
)

object MentionCopy {
    const val LEAD = "Valentin mentioned you in "
    private const val FYI = " (FYI only; read along, do not act unless asked). Context:\n\n"
    private const val MAX_NAME = 192
    private val SESSION = Regex("[A-Za-z0-9_.:-]{1,64}")
    private val BLOCKED = listOf(
        "to", "not", "no", "dont", "don't", "never", "please", "pls", "did", "didnt", "didn't",
        "should", "could", "would", "will", "can", "might", "must", "may", "a", "an", "the",
    )

    enum class Kind { ADDRESS, FYI }

    private data class Span(val start: Int, val visibleEnd: Int, val end: Int)

    fun note(sourceName: String, visible: String): String = LEAD + sourceName + FYI + visible

    fun deliveries(marked: String, live: List<MentionChat>, currentSession: String): List<MentionCopyTarget> {
        if (marked.startsWith(LEAD)) return emptyList()
        val kinds = LinkedHashMap<String, Kind>()
        var i = 0
        while (i < marked.length) {
            val span = markerAt(marked, i)
            if (span == null) {
                i++
                continue
            }
            val session = marked.substring(span.visibleEnd + 1, span.end - 1)
            val chat = live.firstOrNull { it.session == session && it.liveChat }
            val kind = if (chat == null || session == currentSession) null else classify(marked, span)
            if (chat != null && kind != null) {
                val prior = kinds[session]
                kinds[session] = if (prior == Kind.ADDRESS || kind == Kind.ADDRESS) Kind.ADDRESS else Kind.FYI
            }
            i = span.end
        }
        return kinds.filterValues { it == Kind.FYI }.keys.mapNotNull { session ->
            live.firstOrNull { it.session == session && it.liveChat && it.hostProvider.isNotEmpty() && it.hostSessionId.isNotEmpty() }
                ?.let { MentionCopyTarget(it.name, it.session, it.hostProvider, it.hostSessionId) }
        }
    }

    private fun classify(text: String, span: Span): Kind? {
        var start = span.start
        while (start > 0 && text[start - 1] != '\n' && text[start - 1] != '\r') start--
        var end = span.start
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        return classifyLine(text.substring(start, end), Span(span.start - start, span.visibleEnd - start, span.end - start))
    }

    private fun classifyLine(line: String, span: Span): Kind? {
        val lead = skipHSpace(line, 0)
        if (lead >= line.length || line[lead] == '>') return null
        val afterKeyword = matchFyiLead(line, lead)
        if (afterKeyword != null && inMentionChain(line, afterKeyword, span.start)) return Kind.FYI
        if (inMentionChain(line, lead, span.start)) return Kind.ADDRESS
        if (chainReachesLineEnd(line, span) && endsWithFyiKeyword(line.substring(0, chainStartBefore(line, span.start)))) return Kind.FYI
        return null
    }

    private fun markerAt(text: String, pos: Int): Span? {
        if (pos >= text.length || text[pos] != '@' || !boundary(text, pos)) return null
        var i = pos + 1
        if (i >= text.length || text[i] == '{' || space(text[i])) return null
        while (i < text.length && text[i] != '{' && text[i] != '\n' && text[i] != '\r' && i - pos <= MAX_NAME) i++
        if (i >= text.length || text[i] != '{' || space(text[i - 1])) return null
        var j = i + 1
        while (j < text.length && text[j] != '}' && j - i <= 65) j++
        if (j >= text.length || text[j] != '}' || !SESSION.matches(text.substring(i + 1, j))) return null
        return Span(pos, i, j + 1)
    }

    private fun inMentionChain(line: String, start: Int, target: Int): Boolean {
        var p = start
        while (p < line.length) {
            val span = markerAt(line, p) ?: return false
            if (span.start == target) return true
            if (span.start > target) return false
            var nextAt = span.end
            while (nextAt < line.length && line[nextAt] != '@') nextAt++
            if (nextAt >= line.length || !listSeparator(line.substring(span.end, nextAt))) return false
            p = nextAt
        }
        return false
    }

    private fun chainReachesLineEnd(line: String, start: Span): Boolean {
        var current = start
        while (true) {
            var nextAt = current.end
            while (nextAt < line.length && line[nextAt] != '@') nextAt++
            if (nextAt >= line.length) return fyiTrailingOk(line.substring(current.end))
            if (!listSeparator(line.substring(current.end, nextAt))) return false
            current = markerAt(line, nextAt) ?: return false
        }
    }

    private fun chainStartBefore(line: String, targetStart: Int): Int {
        var chainStart: Int? = null
        var lastEnd = 0
        var i = 0
        while (i < targetStart) {
            val marker = markerAt(line, i)
            if (marker != null) {
                if (chainStart == null || !listSeparator(line.substring(lastEnd, marker.start))) chainStart = marker.start
                lastEnd = marker.end
                i = marker.end
            } else {
                i++
            }
        }
        val start = chainStart ?: return targetStart
        return if (listSeparator(line.substring(lastEnd, targetStart))) start else targetStart
    }

    private fun fyiTrailingOk(tail: String): Boolean {
        for (c in tail) if (!hSpace(c) && c != '.' && c != '!' && c != ')' && c != ']') return false
        return true
    }

    private fun matchFyiLead(line: String, start: Int): Int? {
        var p = skipHSpace(line, start)
        if (p < line.length && (line[p] == '(' || line[p] == '[')) p = skipHSpace(line, p + 1)
        val keyword = when {
            p + 3 <= line.length && eqlAscii(line, p, "fyi") -> 3
            p + 2 <= line.length && eqlAscii(line, p, "cc") -> 2
            else -> return null
        }
        p += keyword
        if (p >= line.length) return null
        if (hSpace(line[p])) {
            p = skipHSpace(line, p + 1)
            if (p < line.length && fyiPunct(line[p])) p = skipHSpace(line, p + 1)
            return p
        }
        if (fyiPunct(line[p])) return skipHSpace(line, p + 1)
        return null
    }

    private fun endsWithFyiKeyword(before: String): Boolean {
        var end = trimRight(before).length
        if (end > 0 && fyiPunct(before[end - 1])) end = trimRight(before.substring(0, end - 1)).length
        val keyword = when {
            end >= 3 && eqlAscii(before, end - 3, "fyi") -> 3
            end >= 2 && eqlAscii(before, end - 2, "cc") -> 2
            else -> return false
        }
        val keywordStart = end - keyword
        if (keywordStart > 0) {
            val prev = before[keywordStart - 1]
            if (asciiWordChar(prev) || prev == '@') return false
            val trimmed = trimRight(before.substring(0, keywordStart))
            if (trimmed.isNotEmpty() && asciiWordChar(trimmed[trimmed.length - 1])) {
                var wordStart = trimmed.length
                while (wordStart > 0 && asciiWordChar(trimmed[wordStart - 1])) wordStart--
                if (BLOCKED.any { it == trimmed.substring(wordStart).lowercase() }) return false
            }
        }
        return true
    }

    private fun listSeparator(gap: String): Boolean {
        var trimmed = trimHSpace(gap)
        if (trimmed.isEmpty()) return true
        if (trimmed[0] == ',') {
            trimmed = trimRight(trimmed.substring(1))
            if (trimmed.isEmpty()) return true
        }
        return (trimmed.length == 1 && trimmed[0] == '&') || trimmed.equals("and", ignoreCase = true)
    }

    private fun eqlAscii(text: String, start: Int, ascii: String): Boolean {
        if (start + ascii.length > text.length) return false
        for (i in ascii.indices) {
            val c = text[start + i]
            if ((if (c in 'A'..'Z') c + 32 else c) != ascii[i]) return false
        }
        return true
    }

    private fun fyiPunct(c: Char): Boolean = c == ':' || c == ',' || c == '-' || c == '\u2013' || c == '\u2014'

    private fun hSpace(c: Char): Boolean = c == ' ' || c == '\t'

    private fun space(c: Char): Boolean = hSpace(c) || c == '\r' || c == '\n'

    private fun boundary(text: String, pos: Int): Boolean = pos == 0 || space(text[pos - 1])

    private fun asciiWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '\''

    private fun skipHSpace(text: String, start: Int): Int {
        var i = start
        while (i < text.length && hSpace(text[i])) i++
        return i
    }

    private fun trimHSpace(text: String): String = trimRight(text.substring(skipHSpace(text, 0)))

    private fun trimRight(text: String): String {
        var end = text.length
        while (end > 0 && hSpace(text[end - 1])) end--
        return text.substring(0, end)
    }
}
