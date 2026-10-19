package dev.multiprompt.companion.ui

internal object ReplyQuote {
    fun quote(message: String): String {
        val lines = message.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            .map { it.trimEnd() }
        val visible = if (lines.size > 10) lines.take(10) + "…" else lines
        return visible.joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    }

    fun compose(quoted: String?, message: String): String = when {
        quoted.isNullOrBlank() -> message
        message.isBlank() -> ""
        else -> quote(quoted) + "\n\n" + message
    }

    fun preview(message: String): String = message.lineSequence()
        .filter { it.isNotBlank() }.take(2).joinToString(" ")
}
