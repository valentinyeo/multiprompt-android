package dev.multiprompt.companion.skills

import org.json.JSONArray

data class NativeSkill(val name: String, val description: String)

data class SlashToken(val start: Int, val end: Int, val text: String)

data class SkillSpan(val start: Int, val end: Int, val name: String)

data class SkillInsertion(val text: String, val cursor: Int)

object NativeSkills {
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_./:-]*")

    fun parse(json: String): List<NativeSkill> {
        val rows = JSONArray(json)
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val name = row.optString("name")
                if (!NAME.matches(name)) continue
                add(NativeSkill(name, row.optString("description")))
            }
        }.distinctBy { it.name }.sortedBy { it.name.lowercase() }
    }

    fun token(text: String, cursor: Int): SlashToken? {
        if (cursor !in 1..text.length) return null
        var start = cursor
        while (start > 0 && !wordBreak(text[start - 1])) start--
        if (start == cursor || text[start] != '/') return null
        return SlashToken(start, cursor, text.substring(start + 1, cursor))
    }

    fun query(text: String, cursor: Int): String? = token(text, cursor)?.text

    fun spans(text: String, skills: List<NativeSkill>): List<SkillSpan> {
        if (text.isEmpty() || skills.isEmpty()) return emptyList()
        val names = skills.map { it.name }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        if (names.isEmpty()) return emptyList()
        val found = ArrayList<SkillSpan>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '/' && (index == 0 || wordBreak(text[index - 1]))) {
                val name = names.firstOrNull { candidate ->
                    val end = index + 1 + candidate.length
                    end <= text.length && text.startsWith(candidate, index + 1) && (end == text.length || wordBreak(text[end]))
                }
                if (name != null) {
                    val end = index + 1 + name.length
                    found.add(SkillSpan(index, end, name))
                    index = end
                    continue
                }
            }
            index++
        }
        return found
    }

    fun matches(skills: List<NativeSkill>, query: String): List<NativeSkill> = skills.filter {
        it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
    }

    fun open(text: String): String = if (text.startsWith('/')) text else "/ $text"

    fun insert(text: String, name: String): String {
        val end = text.indexOfFirst(Char::isWhitespace).takeIf { it >= 0 } ?: text.length
        val rest = text.substring(end).trimStart()
        return "/$name $rest"
    }

    fun insert(text: String, name: String, cursor: Int): SkillInsertion {
        val slash = token(text, cursor) ?: return SkillInsertion(text, cursor.coerceIn(0, text.length))
        var wordEnd = slash.start
        while (wordEnd < text.length && !wordBreak(text[wordEnd])) wordEnd++
        val rest = text.substring(wordEnd).trimStart()
        val prefix = text.substring(0, slash.start)
        val head = "/$name"
        val value = if (rest.isEmpty()) "$prefix$head " else "$prefix$head $rest"
        return SkillInsertion(value, prefix.length + head.length + 1)
    }

    private fun wordBreak(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'
}
