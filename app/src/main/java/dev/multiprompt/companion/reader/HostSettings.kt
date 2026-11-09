package dev.multiprompt.companion.reader

import org.json.JSONObject

data class HostSettings(val model: String, val effort: String, val mode: String, val applied: Boolean) {
    companion object {
        fun parse(value: JSONObject) = HostSettings(
            value.optString("model"), value.optString("effort"), value.optString("mode"), value.optBoolean("applied"),
        )

        fun family(name: String?): String? {
            val lower = name?.trim()?.lowercase()?.removePrefix("claude ")?.removePrefix("claude-") ?: return null
            return listOf("opus", "sonnet", "haiku").firstOrNull { lower == it || lower.startsWith("$it ") || lower.startsWith("$it-") }
        }
    }
}

enum class HostChange(val section: String, val label: String, val op: String, val key: String, val value: String, val notice: String) {
    OPUS("Model", "Opus", "set_model", "model", "opus", "Model: Opus"),
    SONNET("Model", "Sonnet", "set_model", "model", "sonnet", "Model: Sonnet"),
    HAIKU("Model", "Haiku", "set_model", "model", "haiku", "Model: Haiku"),
    LOW("Effort", "Low", "set_effort", "effort", "low", "Effort: Low"),
    MEDIUM("Effort", "Medium", "set_effort", "effort", "medium", "Effort: Medium"),
    HIGH("Effort", "High", "set_effort", "effort", "high", "Effort: High"),
    ASK("Permission level", "Ask", "set_permission_mode", "mode", "manual", "Ask on"),
    EDITS("Permission level", "Accept edits", "set_permission_mode", "mode", "acceptEdits", "Accept edits on"),
    PLAN("Permission level", "Plan", "set_permission_mode", "mode", "plan", "Plan on"),
    BYPASS("Permission level", "Bypass", "set_permission_mode", "mode", "bypassPermissions", "Bypass on");

    val failure: String
        get() = if (key == "mode") "Could not change permission mode; try again" else "Could not change chat settings"

    val command: String?
        get() = when (key) {
            "model" -> "/model $value"
            "effort" -> "/effort $value"
            else -> null
        }

    fun line(id: Long, sid: String): String =
        "{\"id\":$id,\"op\":${JSONObject.quote(op)},\"provider\":\"claude\",\"sid\":${JSONObject.quote(sid)},${JSONObject.quote(key)}:${JSONObject.quote(value)}}"

    fun active(settings: HostSettings?, runtimeModel: String?, runtimeEffort: String?): Boolean = when (key) {
        "model" -> value == HostSettings.family(settings?.model?.ifEmpty { null } ?: runtimeModel)
        "effort" -> value == (settings?.effort?.ifEmpty { null } ?: runtimeEffort)?.trim()?.lowercase()
        else -> value == settings?.mode
    }

    companion object {
        fun sections(host: Boolean): List<Pair<String, List<HostChange>>> =
            entries.filter { host || it.command != null }.groupBy { it.section }.toList()
    }
}
