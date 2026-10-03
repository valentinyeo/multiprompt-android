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
        /**
         * [claudeSessionId] is the newest conversation recorded for this folder, read from the
         * host while the session was still alive. With it the restore resumes that exact
         * conversation; without it the plain continue flag picks whichever is newest later.
         */
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
                        ?.takeIf(String::isNotBlank)
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
