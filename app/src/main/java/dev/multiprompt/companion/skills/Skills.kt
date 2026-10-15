package dev.multiprompt.companion.skills

import org.json.JSONArray
import org.json.JSONObject

data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val repo: String,
    val ref: String,
    val source: String = "multiprompt",
)

data class SkillSnapshot(
    val skills: List<Skill>,
    val fetchedAtEpochSeconds: Long,
)

data class SkillSelection(
    val name: String,
    val body: String,
)

object SkillModel {

    const val NO_SKILL: String = ""

    data class ParsedIndex(val entries: List<Entry>) {
        data class Entry(
            val name: String,
            val description: String,
            val file: String,
            val repo: String,
            val ref: String,
            val source: String = "multiprompt",
        )
    }

    fun parseIndex(json: String): ParsedIndex {
        val root = runCatching { JSONObject(json) }.getOrElse { return ParsedIndex(emptyList()) }
        val array = root.optJSONArray("skills") ?: return ParsedIndex(emptyList())
        val entries = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val file = item.optString("file").trim()
                if (name.isEmpty() || file.isEmpty()) continue
                add(
                    ParsedIndex.Entry(
                        name = name,
                        description = item.optString("description").trim(),
                        file = file,
                        repo = item.optString("repo").trim(),
                        ref = item.optString("ref", "main").trim().ifEmpty { "main" },
                        source = item.optString("source", "multiprompt").trim().ifEmpty { "multiprompt" },
                    ),
                )
            }
        }
        return ParsedIndex(entries.sortedBy { it.name.lowercase() })
    }

    fun cacheJson(snapshot: SkillSnapshot): String {
        val array = JSONArray()
        snapshot.skills.forEach { skill ->
            array.put(
                JSONObject()
                    .put("name", skill.name)
                    .put("description", skill.description)
                    .put("body", skill.body)
                    .put("repo", skill.repo)
                    .put("ref", skill.ref)
                    .put("source", skill.source),
            )
        }
        return JSONObject()
            .put("fetchedAtEpochSeconds", snapshot.fetchedAtEpochSeconds)
            .put("skills", array)
            .toString()
    }

    fun parseCache(json: String): SkillSnapshot? = runCatching {
        val obj = JSONObject(json)
        val array = obj.getJSONArray("skills")
        val skills = buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    Skill(
                        name = item.getString("name"),
                        description = item.optString("description"),
                        body = item.getString("body"),
                        repo = item.optString("repo"),
                        ref = item.optString("ref", "main"),
                        source = item.optString("source", "multiprompt"),
                    ),
                )
            }
        }
        SkillSnapshot(skills = sortedByDisplayName(skills), fetchedAtEpochSeconds = obj.getLong("fetchedAtEpochSeconds"))
    }.getOrNull()

    fun sortedByDisplayName(skills: List<Skill>): List<Skill> = skills.sortedBy { it.name.lowercase() }

    fun stripFrontmatter(markdown: String): String {
        val trimmed = markdown.trimStart('\uFEFF', '\n', '\r', ' ', '\t')
        if (!trimmed.startsWith("---")) return markdown
        val end = trimmed.indexOf("\n---", 3)
        if (end < 0) return markdown
        val after = trimmed.substring(end + 4)
        return after.trimStart('\n', '\r')
    }

    fun composeMessage(prompt: String, skill: Skill): String = buildString {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isNotEmpty()) {
            append(trimmedPrompt)
            append("\n\n")
        }
        append("/").append(skill.name)
        append("\n\n<skill name=\"").append(skill.name).append("\">\n")
        append(skill.body.trim())
        append("\n</skill>")
    }
}
