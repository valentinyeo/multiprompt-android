package dev.multiprompt.companion.model

data class TmuxSession(
    val hostId: String,
    val name: String,
    val windows: Int,
    val attachedClients: Int,
    val lastActivityEpochSeconds: Long,
    /** Width the desktop gave this tmux window. The phone renders it without changing it. */
    val columns: Int = 0,
    val rows: Int = 0,
    val workingDirectory: String = "",
    val title: String = "",
    /** The tmux window label, which desktop launchers use for the named session. */
    val windowName: String = "",
    val preview: String = "",
    val paneCommand: String = "",
) {
    val agent: AgentKind
        get() = AgentKind.detect(name, title, preview, paneCommand)

    /**
     * What ZigShell shows on its tabs: the agent's pane title, falling back to the tmux name.
     * Agent TUIs prefix some pane titles with their own monochrome status glyph. The Android UI
     * already renders a colored agent icon, so suppress that duplicate decoration here.
     */
    val displayName: String
        get() {
            val rawName = when {
                usefulWindowName(windowName) -> windowName
                title.isNotBlank() -> title
                else -> name
            }
            if (agent == AgentKind.OTHER) return rawName
            return AGENT_TITLE_PREFIX.replaceFirst(rawName, "").ifBlank { rawName }
        }

    private companion object {
        private val GENERIC_WINDOW_NAMES = setOf(
            "bash", "codex", "fish", "hax", "ksh", "nu", "pwsh", "sh", "zsh", "node",
        )
        private val AGENT_TITLE_PREFIX = Regex("""^\s*[✳✱✢✦✶✻＊*·•●○◉⬡⬢⬣☾☽π›▌]\s+""")

        private fun usefulWindowName(value: String): Boolean =
            value.isNotBlank() && value.trim().lowercase() !in GENERIC_WINDOW_NAMES
    }
}

enum class AgentKind(val label: String) {
    CLAUDE("Claude"),
    CODEX("Codex"),
    PI("Pi"),
    KIMI("Kimi"),
    HAX("Hax"),
    OTHER("Shell");

    companion object {
        fun detect(name: String, title: String = "", preview: String = "", paneCommand: String = ""): AgentKind {
            val command = paneCommand.lowercase().substringAfterLast('/').substringAfterLast('\\')
            when {
                command == "codex" || command.startsWith("codex-") -> return CODEX
                command == "claude" || command.startsWith("claude-") -> return CLAUDE
                command == "pi" || command.startsWith("pi-") -> return PI
                command == "kimi" || command.startsWith("kimi-") -> return KIMI
                command == "hax" || command.startsWith("hax-") -> return HAX
            }

            val normalized = name.lowercase()
            val namedAgent = when {
                "codex" in normalized || normalized.startsWith("cx-") -> CODEX
                "kimi" in normalized -> KIMI
                HAX_NAME.containsMatchIn(normalized) -> HAX
                "claude" in normalized || normalized.startsWith("cl-") -> CLAUDE
                PI_NAME.containsMatchIn(normalized) -> PI
                else -> OTHER
            }
            if (namedAgent != OTHER) return namedAgent

            val terminalText = "$title\n$preview".lowercase()
            return when {
                "openai codex" in terminalText || "codex>" in terminalText -> CODEX
                "claude code" in terminalText || "shift+tab to cycle" in terminalText ||
                    "bypass permissions" in terminalText -> CLAUDE
                "kimi code" in terminalText || "kimi cli" in terminalText -> KIMI
                "pi coding agent" in terminalText -> PI
                "hax ›" in terminalText || "hax v0." in terminalText -> HAX
                else -> OTHER
            }
        }

        private val PI_NAME = Regex("(^|[-_])pi($|[-_])")
        private val HAX_NAME = Regex("(^|[-_])hax($|[-_])")
    }
}
