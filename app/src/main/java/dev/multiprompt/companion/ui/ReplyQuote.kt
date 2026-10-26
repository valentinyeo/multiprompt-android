package dev.multiprompt.companion.ui

internal object ReplyQuote {
    data class Reply(val quoted: String, val body: String) {
        val preview: String get() = ReplyQuote.preview(quoted.lines().joinToString("\n") { it.removePrefix(">").removePrefix(" ") })
    }

    fun parse(message: String): Reply? {
        val lines = message.lines()
        val end = lines.indexOfFirst { it != ">" && !it.startsWith("> ") }
        if (end <= 0 || !lines[end].isBlank()) return null
        val quoted = lines.take(end).joinToString("\n")
        if (lines.take(end).all { it.removePrefix(">").isBlank() }) return null
        val body = lines.drop(end).dropWhile { it.isBlank() }.joinToString("\n")
        if (body.isBlank()) return null
        return Reply(quoted, body)
    }

    fun sourceIndex(messages: List<String>, before: Int, quoted: String): Int? =
        (0 until before.coerceIn(0, messages.size)).lastOrNull { quote(messages[it]) == quoted }

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
