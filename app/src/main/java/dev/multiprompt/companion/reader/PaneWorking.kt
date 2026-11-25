package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.TmuxText

internal const val WORKING_TAIL_LINES = 200

internal fun isTransientProgress(value: String): Boolean = value.lineSequence().all { line ->
    val trimmed = line.trim()
    trimmed.isBlank() || listOf(
        "working", "running", "thinking", "reading", "searching", "esc to cancel",
    ).any { trimmed.startsWith(it, ignoreCase = true) }
}

internal fun tailLines(value: String, count: Int): String {
    if (count <= 0) return ""
    var index = value.length
    repeat(count) {
        index = value.lastIndexOf('\n', index - 1)
        if (index < 0) return value
    }
    return value.substring(index + 1)
}

internal fun paneWorking(screenOutput: String, agent: AgentKind): Boolean =
    TmuxText.readerBlocks(tailLines(screenOutput, WORKING_TAIL_LINES), agent).any {
        it.kind == TmuxText.ReaderBlockKind.PROGRESS && isTransientProgress(it.text)
    }
