package dev.multiprompt.companion.model

data class RowParts(
    val question: String = "",
    val step: String = "",
    val reply: String = "",
    val activityEpochSeconds: Long = 0,
    val replyEpochSeconds: Long = 0,
) {
    fun isCurrentFor(session: TmuxSession): Boolean =
        activityEpochSeconds == session.lastActivityEpochSeconds &&
            replyEpochSeconds == session.replyActivityEpochSeconds
}
