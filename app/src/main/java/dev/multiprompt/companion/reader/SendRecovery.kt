package dev.multiprompt.companion.reader

object SendRecovery {
    const val ATTEMPTS = 2

    fun neverAttempted(row: MessageProtocol.Row): Boolean =
        row.state == MessageProtocol.State.FAILED && row.receipt.isNullOrBlank() && row.text.isNotBlank()

    fun due(row: MessageProtocol.Row, recovered: Int, connected: Boolean): Boolean =
        connected && recovered < ATTEMPTS && neverAttempted(row)
}
