package dev.multiprompt.companion.ssh

enum class CancelOutcome { CANCELLED, ALREADY_TAKEN }

object QueueCancel {
    fun parse(stdout: String): CancelOutcome {
        val status = stdout.lineSequence().map { it.trim() }
            .lastOrNull { it.startsWith(TmuxCommands.CANCEL_PREFIX) }
            ?.removePrefix(TmuxCommands.CANCEL_PREFIX)
        return when (status) {
            "ok" -> CancelOutcome.CANCELLED
            "not_queued" -> CancelOutcome.ALREADY_TAKEN
            null -> throw SshProblem.Connection("Could not cancel the message: the host did not answer")
            "unsupported" -> throw SshProblem.Connection("This Claude version cannot cancel a queued message")
            "draft_not_restorable" -> throw SshProblem.Connection("Could not cancel the message: the text in Claude's prompt box holds pasted content that cannot be put back, so clear it first")
            "queue_mismatch" -> throw SshProblem.Connection("Could not cancel the message: Claude's queue was not what the app expected, so the waiting messages are in Claude's prompt box")
            else -> throw SshProblem.Connection("Could not cancel the message: $status")
        }
    }
}
