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
            else -> throw SshProblem.Connection("Could not cancel the message: $status")
        }
    }
}
