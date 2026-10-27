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
        return Regex("(?:enter to (?:select|submit)|space to (?:select|toggle)|esc to cancel)", RegexOption.IGNORE_CASE).containsMatchIn(screen) && compact(title).isNotEmpty() && compact(title) in compact(screen) &&
            options.all { compact(it.label) in compact(screen) }
    }

    companion object {
        fun parse(callId: String, index: Int, value: JSONObject, provider: String): PendingQuestion {
            val options = value.getJSONArray("options")
            return PendingQuestion(callId, index, value.getString("question"), value.optString("header"),
                (0 until options.length()).map { options.getJSONObject(it).let { option ->
                    Option(option.getString("label"), option.optString("description"))
                } }, value.optBoolean("multiSelect"), value.optBoolean("other", true), provider).also {
                require(it.callId.isNotBlank() && it.title.isNotBlank() && it.options.isNotEmpty())
            }
        }
    }
}
