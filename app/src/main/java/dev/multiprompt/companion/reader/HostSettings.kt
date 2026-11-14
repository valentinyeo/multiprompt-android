package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject

data class HostSettings(val model: String, val effort: String, val mode: String, val applied: Boolean) {
    companion object {
        fun parse(value: JSONObject) = HostSettings(
            value.optString("model"), value.optString("effort"), value.optString("mode"), value.optBoolean("applied"),
        )

        fun family(name: String?): String? {
            val lower = name?.trim()?.lowercase()?.removePrefix("claude ")?.removePrefix("claude-") ?: return null
            return listOf("opus", "sonnet", "haiku", "fable").firstOrNull { lower == it || lower.startsWith("$it ") || lower.startsWith("$it-") }
        }
    }
}

enum class HostChange(val section: String, val label: String, val op: String, val key: String, val value: String, val notice: String, val provider: String = "claude") {
    OPUS("Model", "Opus", "set_model", "model", "opus", "Model: Opus"),
    SONNET("Model", "Sonnet", "set_model", "model", "sonnet", "Model: Sonnet"),
    HAIKU("Model", "Haiku", "set_model", "model", "haiku", "Model: Haiku"),
    FABLE("Model", "Fable", "set_model", "model", "fable", "Model: Fable"),
    LOW("Effort", "Low", "set_effort", "effort", "low", "Effort: Low"),
    MEDIUM("Effort", "Medium", "set_effort", "effort", "medium", "Effort: Medium"),
    HIGH("Effort", "High", "set_effort", "effort", "high", "Effort: High"),
    ASK("Permission level", "Ask", "set_permission_mode", "mode", "manual", "Ask on"),
    EDITS("Permission level", "Accept edits", "set_permission_mode", "mode", "acceptEdits", "Accept edits on"),
    PLAN("Permission level", "Plan", "set_permission_mode", "mode", "plan", "Plan on"),
    BYPASS("Permission level", "Bypass", "set_permission_mode", "mode", "bypassPermissions", "Bypass on"),
    SOL("Model", "Sol", "set_model", "model", "sol", "Model: Sol", "codex"),
    TERRA("Model", "Terra", "set_model", "model", "terra", "Model: Terra", "codex"),
    LUNA("Model", "Luna", "set_model", "model", "luna", "Model: Luna", "codex"),
    ASTRA("Model", "Astra", "set_model", "model", "astra", "Model: Astra", "codex");

    val failure: String
        get() = if (key == "mode") "Could not change permission mode; try again" else "Could not change chat settings"

    val command: String?
        get() = if (provider != "claude") null else when (key) {
            "model" -> "/model $value"
            "effort" -> "/effort $value"
            else -> null
        }

    fun line(id: Long, sid: String): String =
        "{\"id\":$id,\"op\":${JSONObject.quote(op)},\"provider\":${JSONObject.quote(provider)},\"sid\":${JSONObject.quote(sid)},${JSONObject.quote(key)}:${JSONObject.quote(value)}}"

    fun active(settings: HostSettings?, runtimeModel: String?, runtimeEffort: String?): Boolean = if (provider != "claude") value == settings?.model else when (key) {
        "model" -> value == HostSettings.family(settings?.model?.ifEmpty { null } ?: runtimeModel)
        "effort" -> value == (settings?.effort?.ifEmpty { null } ?: runtimeEffort)?.trim()?.lowercase()
        else -> value == settings?.mode
    }

    companion object {
        fun sections(host: Boolean, provider: String = "claude"): List<Pair<String, List<HostChange>>> =
            entries.filter { it.provider == provider && (host || it.command != null) }.groupBy { it.section }.toList()

        fun providerOf(agent: AgentKind): String? = agent.hostProvider?.takeIf { it == "claude" || it == "codex" }
    }
}
