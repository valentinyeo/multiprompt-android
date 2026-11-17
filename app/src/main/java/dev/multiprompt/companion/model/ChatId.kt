package dev.multiprompt.companion.model

object ChatId {
    const val COPIED = "Chat ID copied"

    fun of(session: TmuxSession): String? =
        session.hostSessionId.ifBlank { session.name }.ifBlank { null }
}
