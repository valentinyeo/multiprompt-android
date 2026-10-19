package dev.multiprompt.companion.terminal

internal fun shellHistoryLines(output: String): List<String> = output
    .replace(Regex("(?:\u001B\\[|\u009B)[0-?]*[ -/]*[@-~]"), "")
    .replace(Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"), "")
    .replace(Regex("\u001B[@-_]"), "")
    .filter { it == '\n' || it == '\t' || !it.isISOControl() }
    .lines()
    .takeLast(5000)
