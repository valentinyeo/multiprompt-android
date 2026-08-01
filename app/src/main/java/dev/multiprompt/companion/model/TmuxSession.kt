package dev.multiprompt.companion.model

data class TmuxSession(
    val hostId: String,
    val name: String,
    val windows: Int,
    val attachedClients: Int,
    val lastActivityEpochSeconds: Long,
) {
    val agent: AgentKind
        get() = AgentKind.fromName(name)
}

enum class AgentKind(val label: String) {
    CLAUDE("Claude"),
    CODEX("Codex"),
    KIMI("Kimi"),
    OTHER("Shell");

    companion object {
        fun fromName(name: String): AgentKind {
            val normalized = name.lowercase()
            return when {
                "codex" in normalized || normalized.startsWith("cx-") -> CODEX
                "kimi" in normalized -> KIMI
                "claude" in normalized || normalized.startsWith("cl-") -> CLAUDE
                else -> OTHER
            }
        }
    }
}

