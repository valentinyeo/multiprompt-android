package dev.multiprompt.companion.model

data class DissolvedSession(
    val hostId: String,
    val tmuxSessionName: String,
    val displayName: String,
    val agent: AgentKind,
    val workingDirectory: String,
    val resumeCommand: String,
    val workspaceId: String? = null,
    val workspaceName: String = "",
    val dissolvedAtEpochSeconds: Long = 0L,
) {
    val key: String get() = "$hostId::$tmuxSessionName"

    companion object {
        private val CLAUDE_SESSION_ID = Regex("^[A-Za-z0-9-]{1,64}$")

        fun validClaudeSessionId(id: String): Boolean = CLAUDE_SESSION_ID.matches(id)

        fun from(
            session: TmuxSession,
            workspaceId: String?,
            workspaceName: String,
            claudeSessionId: String? = null,
        ): DissolvedSession =
            DissolvedSession(
                hostId = session.hostId,
                tmuxSessionName = session.name,
                displayName = session.displayName,
                agent = session.agent,
                workingDirectory = session.workingDirectory,
                resumeCommand = when (session.agent) {
                    AgentKind.CLAUDE -> claudeSessionId
                        ?.takeIf(::validClaudeSessionId)
                        ?.let { "claude --resume $it" }
                        ?: "claude --continue"
                    AgentKind.CODEX -> "codex resume --last"
                    AgentKind.PI -> "pi --continue"
                    AgentKind.HAX -> "hax --continue"
                    AgentKind.CURSOR -> "cursor-agent --continue"
                    else -> ""
                },
                workspaceId = workspaceId,
                workspaceName = workspaceName,
                dissolvedAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
    }
}
