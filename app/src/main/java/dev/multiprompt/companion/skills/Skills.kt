package dev.multiprompt.companion.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * A skill multiprompt maintains itself, independent of any coding harness.
 * Source is one or more GitHub repos; [body] carries the full SKILL.md text (minus
 * frontmatter) so the agent in the tmux session receives the real instructions even
 * when its harness has never seen this skill.
 */
data class Skill(
    /** Short name used for `/name` in the composer. */
    val name: String,
    /** One-line description shown in the picker submenu. */
    val description: String,
    /** Full skill text (markdown body, frontmatter stripped). */
    val body: String,
    /** Origin repo, for display and refresh bookkeeping. */
    val repo: String,
    /** Commit/ref the body was fetched from. */
    val ref: String,
    /** "multiprompt" = shipped/curated by multiprompt; "harness" = bundled from the coding harness. */
    val source: String = "multiprompt",
)

/**
 * Immutable snapshot of the fetched skill list — the cache layer persists
 * [SkillCacheEntry] and exposes [SkillSnapshot]s for the UI.
 */
data class SkillSnapshot(
    val skills: List<Skill>,
    val fetchedAtEpochSeconds: Long,
)

/** Selection made in the picker: what the composer inserts and what gets sent. */
data class SkillSelection(
    val name: String,
    val body: String,
)

/**
 * Pure-JVM parsing/formatting for the skills feature (no Android imports):
 * - parses a skills index JSON ({"skills":[{"name","description","file","repo","ref"}]})
 * - builds the request for fetching a skill body
 * - composes the message actually sent to the agent
 */
object SkillModel {

    /** Sentinel used by senders when no skill is attached. */
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

    /**
     * Parses the index document fetched from GitHub. Deterministic: entries are sorted
     * by name (case-insensitive); blank names/files are rejected by skipping.
     */
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

    /** Serializes a snapshot for caching in prefs (round-trips via [parseCache]). */
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

    /** Strips a SKILL.md frontmatter block so the body reads as instructions. */
    fun stripFrontmatter(markdown: String): String {
        val trimmed = markdown.trimStart('\uFEFF', '\n', '\r', ' ', '\t')
        if (!trimmed.startsWith("---")) return markdown
        val end = trimmed.indexOf("\n---", 3)
        if (end < 0) return markdown
        val after = trimmed.substring(end + 4)
        return after.trimStart('\n', '\r')
    }

    /**
     * The text sent to the agent: the user's prompt plus the full skill content, in a
     * fenced block so any harness treats it as literal instructions rather than chat.
     */
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
