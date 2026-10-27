package dev.multiprompt.companion.reader

import org.json.JSONObject

data class PendingQuestion(
    val callId: String,
    val index: Int,
    val title: String,
    val header: String,
    val options: List<Option>,
    val multiSelect: Boolean,
    val other: Boolean,
    val provider: String,
) {
    data class Option(val label: String, val description: String)

    fun isVisible(screen: String): Boolean {
        fun compact(text: String) = text.filterNot(Char::isWhitespace)
        val lines = screen.lines().map(String::trim)
        val footer = Regex("(?:enter to (?:select|submit)|space to (?:select|toggle)|esc to cancel)", RegexOption.IGNORE_CASE)
        val end = lines.indexOfLast { footer.containsMatchIn(it) }
        if (end < 0 || lines.drop(end + 1).any { it.isNotEmpty() && !it.all { ch -> ch in "─━-" } }) return false
        val cursor = (0 until end).lastOrNull { Regex("^[❯›>]\\s*[1-9][.)]").containsMatchIn(lines[it]) }
        if (options.isNotEmpty() && cursor == null) return false
        val start = cursor?.let { current ->
            (0 until current).lastOrNull { lines[it].isNotEmpty() && lines[it].all { ch -> ch in "─━-" } }?.plus(1)
        } ?: 0
        val form = compact(lines.subList(start, end).joinToString("\n"))
        return compact(title).isNotEmpty() && compact(title) in form && options.all { compact(it.label) in form }
    }

    fun json(): JSONObject = JSONObject().put("question", title).put("header", header)
        .put("multiSelect", multiSelect).put("other", other)
        .put("options", org.json.JSONArray(options.map { JSONObject().put("label", it.label).put("description", it.description) }))

    companion object {
        fun parse(callId: String, index: Int, value: JSONObject, provider: String): PendingQuestion {
            val options = value.optJSONArray("options") ?: org.json.JSONArray()
            return PendingQuestion(callId, index, value.getString("question"), value.optString("header"),
                (0 until options.length()).map { options.getJSONObject(it).let { option ->
                    Option(option.getString("label"), option.optString("description"))
                } }, value.optBoolean("multiSelect"), value.optBoolean("other", provider == "claude" || options.length() == 0 || value.optBoolean("isOther")), provider).also {
                require(it.callId.isNotBlank() && it.title.isNotBlank() && (it.options.isNotEmpty() || it.other))
            }
        }
    }
}
