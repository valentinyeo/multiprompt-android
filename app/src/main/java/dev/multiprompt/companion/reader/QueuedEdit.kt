package dev.multiprompt.companion.reader

object QueuedEdit {
    sealed interface Outcome {
        data class Sent(val admission: PromptAdmission) : Outcome
        data object TooLate : Outcome
        data class Lost(val reason: String) : Outcome
    }

    const val TOO_LATE_NOTICE = "Could not edit. It may already be with your agent"

    fun shows(message: ChatHistoryMessage): Boolean =
        message.role == "user" && message.cancellable && message.requestId.isNotEmpty()

    suspend fun run(
        row: MessageProtocol.Row?,
        text: String,
        dismiss: suspend (MessageProtocol.Row) -> MessageActionResult,
        submit: suspend (String, String) -> PromptAdmission,
    ): Outcome {
        if (row == null || (row.state != MessageProtocol.State.QUEUED && row.state != MessageProtocol.State.SENT)) return Outcome.TooLate
        if (dismiss(row) != MessageActionResult.Applied) return Outcome.TooLate
        val admission = submit(text, row.id)
        return if (admission is PromptAdmission.Rejected) Outcome.Lost(admission.reason) else Outcome.Sent(admission)
    }
}
