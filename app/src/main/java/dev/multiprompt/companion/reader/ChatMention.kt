package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.TmuxSession

data class MentionChat(val name: String, val session: String)

data class MentionQuery(val start: Int, val text: String)

object ChatMention {
    private val SESSION = Regex("[A-Za-z0-9_.:-]{1,64}")
    private const val MAX_NAME = 192

    fun chats(sessions: List<TmuxSession>, current: TmuxSession): List<MentionChat> = sessions
        .filter { it.hostId == current.hostId && it.name != current.name && SESSION.matches(it.name) }
        .mapNotNull { session -> session.displayName.trim().takeIf { it.isNotEmpty() && it.length < MAX_NAME }?.let { MentionChat(it, session.name) } }
        .distinct()

    fun query(text: String, cursor: Int): MentionQuery? {
        if (cursor !in 1..text.length) return null
        var at = cursor - 1
        while (at >= 0 && text[at] != '@') {
            if (text[at].isWhitespace() || text[at] == '{' || text[at] == '}') return null
            at--
        }
        if (at < 0 || !boundary(text, at)) return null
        return MentionQuery(at, text.substring(at + 1, cursor))
    }

    fun options(chats: List<MentionChat>, query: String): List<MentionChat> {
        val q = query.trim()
        if (q.isEmpty()) return chats
        fun rank(name: String): Int = when {
            name.startsWith(q, ignoreCase = true) -> 0
            name.split(' ').any { it.startsWith(q, ignoreCase = true) } -> 1
            name.contains(q, ignoreCase = true) -> 2
            else -> 3
        }
        return chats.map { it to rank(it.name) }.filter { it.second < 3 }.sortedBy { it.second }.map { it.first }
    }

    fun pick(text: String, query: MentionQuery, cursor: Int, chat: MentionChat): Pair<String, Int> {
        val inserted = "@${chat.name} "
        val rest = text.substring(cursor).let { if (it.startsWith(' ')) it.substring(1) else it }
        val value = text.substring(0, query.start) + inserted + rest
        return value to query.start + inserted.length
    }

    fun annotate(text: String, picks: List<MentionChat>, chats: List<MentionChat>): String {
        val used = BooleanArray(picks.size)
        val out = StringBuilder()
        var i = 0
        var changed = false
        while (i < text.length) {
            val marker = markerEnd(text, i)
            if (marker != null) {
                out.append(text, i, marker)
                i = marker
                continue
            }
            val name = namedAt(text, i, picks + chats)
            if (name == null) {
                out.append(text[i])
                i++
                continue
            }
            out.append('@').append(name)
            resolve(name, picks, used, chats)?.let {
                out.append('{').append(it).append('}')
                changed = true
            }
            i += name.length + 1
        }
        return if (changed) out.toString() else text
    }

    fun visible(text: String): String {
        if ('{' !in text) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val open = markerOpen(text, i)
            val end = open?.let { markerEnd(text, i) }
            if (open != null && end != null) {
                out.append(text, i, open)
                i = end
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun boundary(text: String, pos: Int) = pos == 0 || text[pos - 1].isWhitespace()

    private fun markerOpen(text: String, pos: Int): Int? {
        if (pos >= text.length || text[pos] != '@' || !boundary(text, pos)) return null
        val first = text.getOrNull(pos + 1) ?: return null
        if (first == '{' || first.isWhitespace()) return null
        var i = pos + 1
        while (i < text.length && text[i] != '{' && text[i] != '\n' && text[i] != '\r' && i - pos <= MAX_NAME) i++
        if (i >= text.length || text[i] != '{' || text[i - 1].isWhitespace()) return null
        return i
    }

    private fun markerEnd(text: String, pos: Int): Int? {
        val open = markerOpen(text, pos) ?: return null
        val close = text.indexOf('}', open + 1).takeIf { it > open && it - open <= 65 } ?: return null
        return if (SESSION.matches(text.substring(open + 1, close))) close + 1 else null
    }

    private fun namedAt(text: String, pos: Int, chats: List<MentionChat>): String? {
        if (text[pos] != '@' || !boundary(text, pos)) return null
        return chats.map { it.name }.filter { name ->
            text.startsWith(name, pos + 1) && (pos + 1 + name.length).let { next -> next == text.length || text[next].isWhitespace() }
        }.maxByOrNull { it.length }
    }

    private fun resolve(name: String, picks: List<MentionChat>, used: BooleanArray, chats: List<MentionChat>): String? {
        picks.forEachIndexed { index, pick ->
            if (!used[index] && pick.name == name) {
                used[index] = true
                return pick.session
            }
        }
        return chats.filter { it.name == name }.map { it.session }.distinct().singleOrNull()
    }
}
