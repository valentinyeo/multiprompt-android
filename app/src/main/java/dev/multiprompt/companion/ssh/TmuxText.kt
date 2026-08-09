package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.AgentKind

object TmuxText {
    enum class ReaderBlockKind {
        PROSE,
        USER_PROMPT,
        CODE,
        PROGRESS,
    }

    data class ReaderBlock(
        val kind: ReaderBlockKind,
        val text: String,
        val language: String? = null,
        val filePath: String? = null,
    )

    fun decodeHex(value: String): String {
        if (value.length % 2 != 0 || value.any { it.digitToIntOrNull(16) == null }) return ""
        val bytes = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Removes desktop TUI margins so captured output reads like a mobile transcript. */
    fun leftAligned(value: String): String = value
        .lineSequence()
        .joinToString("\n") { it.trimStart() }
        .trim()

    /** Hides the active terminal composer because Android provides its own native composer. */
    fun withoutActiveComposer(value: String, agent: AgentKind = AgentKind.OTHER): String {
        val lines = value.lines()
        val searchStart = (lines.size - COMPOSER_SEARCH_LINES).coerceAtLeast(0)
        val promptIndex = (lines.lastIndex downTo searchStart).firstOrNull { index ->
            val line = lines[index].trimStart()
            isPromptMarker(line, agent)
        } ?: return value
        val dividerIndex = ((promptIndex - 1) downTo (promptIndex - 4).coerceAtLeast(searchStart))
            .lastOrNull { index -> isDivider(lines[index]) }
        return lines.take(dividerIndex ?: promptIndex).joinToString("\n").trimEnd()
    }

    /** Converts a terminal snapshot into conservative, display-only Reader sections. */
    fun readerBlocks(value: String, agent: AgentKind = AgentKind.OTHER): List<ReaderBlock> {
        val blocks = mutableListOf<ReaderBlock>()
        val lines = value.lines()
        val current = StringBuilder()
        var currentKind = ReaderBlockKind.PROSE
        var fencedCode = false
        var fencedLanguage: String? = null

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotBlank()) {
                blocks += ReaderBlock(
                    kind = currentKind,
                    text = text,
                    language = fencedLanguage ?: text.takeIf { currentKind == ReaderBlockKind.CODE }
                        ?.let(::inferLanguage),
                    filePath = text.takeIf { currentKind == ReaderBlockKind.CODE }
                        ?.let(::inferFilePath),
                )
            }
            current.clear()
            fencedLanguage = null
        }

        fun append(kind: ReaderBlockKind, line: String) {
            if (current.isNotEmpty() && currentKind != kind) flush()
            currentKind = kind
            if (current.isNotEmpty()) current.append('\n')
            current.append(
                if (kind == ReaderBlockKind.USER_PROMPT) removePromptMarker(line, agent) else line.trimEnd(),
            )
        }

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("``")) {
                if (current.isNotEmpty()) flush()
                if (fencedCode) {
                    fencedCode = false
                    currentKind = ReaderBlockKind.PROSE
                } else {
                    fencedCode = true
                    currentKind = ReaderBlockKind.CODE
                    fencedLanguage = trimmed.removePrefix("```").trim().ifBlank { null }
                }
                return@forEach
            }
            if (trimmed.isBlank()) {
                if (current.isNotEmpty()) {
                    if (agent == AgentKind.CODEX && currentKind == ReaderBlockKind.PROGRESS) {
                        flush()
                    } else {
                        current.append('\n')
                    }
                }
                return@forEach
            }
            val kind = when {
                looksLikeUserPrompt(line, agent) -> ReaderBlockKind.USER_PROMPT
                fencedCode || looksLikeCode(line) -> ReaderBlockKind.CODE
                looksLikeProgress(line, agent) -> ReaderBlockKind.PROGRESS
                agent == AgentKind.CODEX && currentKind == ReaderBlockKind.PROGRESS -> {
                    // Codex renders command output as a bullet followed by unmarked wrapped
                    // lines. Keep that output in the same collapsed activity section until the
                    // next blank line or a new semantic block.
                    ReaderBlockKind.PROGRESS
                }
                else -> ReaderBlockKind.PROSE
            }
            append(kind, line)
        }
        flush()
        return blocks
    }

    /** Best-effort Android equivalent of ZigShell's agent DONE event. */
    fun isWaitingForInput(value: String): Boolean {
        val tail = value.lineSequence()
            .map(String::trimStart)
            .filter(String::isNotBlank)
            .toList()
            .takeLast(INPUT_SEARCH_LINES)
        return tail.any { line ->
            line.startsWith("❯") ||
                line.startsWith("›") ||
                IDLE_INPUT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
        }
    }

    private fun isDivider(value: String): Boolean {
        val compact = value.filterNot(Char::isWhitespace)
        return compact.length >= 8 && compact.all { it in DIVIDER_CHARACTERS }
    }

    private fun looksLikeUserPrompt(value: String, agent: AgentKind): Boolean =
        isPromptMarker(value.trimStart(), agent)

    private fun isPromptMarker(value: String, agent: AgentKind): Boolean {
        val line = value.trimStart()
        return when {
            line.startsWith("❯") -> true
            line.startsWith("›") -> true
            agent == AgentKind.PI && line.startsWith("> ") -> true
            else -> false
        } ||
            line.startsWith("User:", ignoreCase = true) ||
            line.startsWith("You:", ignoreCase = true)
    }

    private fun removePromptMarker(value: String, agent: AgentKind): String = value.trimStart()
        .removePrefix("❯")
        .removePrefix("›")
        .let { if (agent == AgentKind.PI) it.removePrefix("> ") else it }
        .removePrefix("User:")
        .removePrefix("user:")
        .removePrefix("You:")
        .removePrefix("you:")
        .trim()

    private fun looksLikeProgress(value: String, agent: AgentKind): Boolean {
        val line = value.trimStart()
        return line.startsWith("⏺") || line.startsWith("⎿") ||
            line.startsWith("✓") || line.startsWith("✗") ||
            line.startsWith("⚠") || line.startsWith("Working", ignoreCase = true) ||
            line.startsWith("Running", ignoreCase = true) ||
            line.startsWith("Thinking", ignoreCase = true) ||
            line.startsWith("Reading", ignoreCase = true) ||
            line.startsWith("Searching", ignoreCase = true) ||
            line.startsWith("Esc to cancel", ignoreCase = true) ||
            line.startsWith("Tool:", ignoreCase = true) ||
            (agent == AgentKind.CODEX && (
                line.startsWith("•") ||
                    line.startsWith("└") ||
                    line.startsWith("│") ||
                    line.startsWith("… +") ||
                    line.startsWith("... +") ||
                    line.startsWith("ctrl + t", ignoreCase = true) ||
                    line.startsWith("esc to interrupt", ignoreCase = true)
                )) ||
            (agent == AgentKind.PI && (line.startsWith("→") || line.startsWith("←"))) ||
            TOOL_CALL_MARKERS.any { line.startsWith(it) }
    }

    private fun inferLanguage(value: String): String? {
        val firstLine = value.lineSequence().firstOrNull().orEmpty().trimStart()
        return when {
            firstLine.startsWith("diff --git") || value.contains("@@ ") ||
                value.lineSequence().any { it.trimStart().matches(NUMBERED_DIFF_LINE) } -> "diff"
            firstLine.startsWith("#!/") -> "shell"
            value.contains("fun ") || value.contains("val ") || value.contains("package ") -> "kotlin"
            value.contains("const ") || value.contains("function ") || value.contains("=>") -> "javascript"
            value.contains("def ") || value.contains("import ") && value.contains(":") -> "python"
            value.trimStart().startsWith("{") || value.trimStart().startsWith("[") -> "json"
            else -> null
        }
    }

    private fun inferFilePath(value: String): String? {
        val diffPath = DIFF_PATH.find(value)?.groupValues?.getOrNull(1)
        if (!diffPath.isNullOrBlank()) return diffPath
        return FILE_PATH.find(value)?.groupValues?.getOrNull(1)
    }

    private fun looksLikeCode(value: String): Boolean {
        val line = value.trimStart()
        return line.startsWith("diff --git ") ||
            line.startsWith("index ") ||
            line.startsWith("@@") ||
            line.startsWith("+++") ||
            line.startsWith("---") ||
            (line.length > 1 && (line.startsWith("+") || line.startsWith("-")) &&
                line[1] != ' ') ||
            line.matches(NUMBERED_DIFF_LINE) ||
            line.startsWith("package ") ||
            line.startsWith("import ") ||
            line.startsWith("#!/") ||
            line.startsWith("#include ") ||
            line.startsWith("git diff ") ||
            line.matches(CODE_LINE) ||
            line.endsWith(" {") ||
            line.endsWith("}") ||
            line.endsWith(";")
    }

    private const val COMPOSER_SEARCH_LINES = 18
    private const val INPUT_SEARCH_LINES = 24
    private const val DIVIDER_CHARACTERS = "─━═╌╍-_▔▁"
    private val CODE_LINE = Regex("(?:fun|class|interface|object|const|val|var|return|if|for|while)\\b.*")
    private val NUMBERED_DIFF_LINE = Regex("\\d+\\s+[+-](?:\\s|$).*")
    private val DIFF_PATH = Regex("diff --git a/\\S+ b/(\\S+)")
    private val FILE_PATH = Regex("(?:^|\\n)(?:\\+\\+\\+ b/|File: )([^\\s]+)")
    private val TOOL_CALL_MARKERS = listOf(
        "Read(", "Edit(", "Write(", "Bash(", "Glob(", "Grep(", "Task(", "WebFetch(",
    )
    private val IDLE_INPUT_MARKERS = listOf(
        "new task? /clear to save",
        "press up to edit queued messages",
        "do you want to proceed?",
        "would you like to proceed?",
    )
}
