package dev.multiprompt.companion.skills

import org.json.JSONArray

data class NativeSkill(val name: String, val description: String)

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

    fun query(text: String, cursor: Int): String? {
        if (!text.startsWith('/') || cursor !in 1..text.length) return null
        val query = text.substring(1, cursor)
        return query.takeUnless { it.any(Char::isWhitespace) }
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
}
