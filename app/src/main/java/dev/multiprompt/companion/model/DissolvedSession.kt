package dev.multiprompt.companion.model

/** A tmux session dissolved from the VPS but retained for later resurrection. */
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
        fun from(session: TmuxSession, workspaceId: String?, workspaceName: String): DissolvedSession =
            DissolvedSession(
                hostId = session.hostId,
                tmuxSessionName = session.name,
                displayName = session.displayName,
                agent = session.agent,
                workingDirectory = session.workingDirectory,
                resumeCommand = when (session.agent) {
                    AgentKind.CLAUDE -> "claude --continue"
                    AgentKind.CODEX -> "codex resume --last"
                    AgentKind.HAX -> "hax --continue"
                    else -> ""
                },
                workspaceId = workspaceId,
                workspaceName = workspaceName,
                dissolvedAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
    }
}
