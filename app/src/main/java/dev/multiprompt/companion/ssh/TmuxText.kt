package dev.multiprompt.companion.ssh

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
    fun withoutActiveComposer(value: String): String {
        val lines = value.lines()
        val searchStart = (lines.size - COMPOSER_SEARCH_LINES).coerceAtLeast(0)
        val promptIndex = (lines.lastIndex downTo searchStart).firstOrNull { index ->
            val line = lines[index].trimStart()
            line.startsWith("❯") || line.startsWith("›")
        } ?: return value
        val dividerIndex = ((promptIndex - 1) downTo (promptIndex - 4).coerceAtLeast(searchStart))
            .lastOrNull { index -> isDivider(lines[index]) }
        return lines.take(dividerIndex ?: promptIndex).joinToString("\n").trimEnd()
    }

    /** Converts a terminal snapshot into conservative, display-only Reader sections. */
    fun readerBlocks(value: String): List<ReaderBlock> {
        val blocks = mutableListOf<ReaderBlock>()
        val lines = value.lines()
        val current = StringBuilder()
        var currentKind = ReaderBlockKind.PROSE
        var fencedCode = false

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotBlank()) blocks += ReaderBlock(currentKind, text)
            current.clear()
        }

        fun append(kind: ReaderBlockKind, line: String) {
            if (current.isNotEmpty() && currentKind != kind) flush()
            currentKind = kind
            if (current.isNotEmpty()) current.append('\n')
            current.append(
                if (kind == ReaderBlockKind.USER_PROMPT) removePromptMarker(line) else line.trimEnd(),
            )
        }

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("``")) {
                if (fencedCode) flush()
                fencedCode = !fencedCode
                if (!fencedCode) currentKind = ReaderBlockKind.PROSE
                return@forEach
            }
            if (trimmed.isBlank()) {
                if (current.isNotEmpty()) current.append('\n')
                return@forEach
            }
            val kind = when {
                fencedCode || looksLikeCode(line) -> ReaderBlockKind.CODE
                looksLikeUserPrompt(line) -> ReaderBlockKind.USER_PROMPT
                looksLikeProgress(line) -> ReaderBlockKind.PROGRESS
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

    private fun looksLikeUserPrompt(value: String): Boolean {
        val line = value.trimStart()
        return line.startsWith("❯") || line.startsWith("›") ||
            line.startsWith("User:", ignoreCase = true) ||
            line.startsWith("You:", ignoreCase = true)
    }

    private fun removePromptMarker(value: String): String = value.trimStart()
        .removePrefix("❯")
        .removePrefix("›")
        .removePrefix("User:")
        .removePrefix("user:")
        .removePrefix("You:")
        .removePrefix("you:")
        .trim()

    private fun looksLikeProgress(value: String): Boolean {
        val line = value.trimStart()
        return line.startsWith("⏺") || line.startsWith("⎿") ||
            line.startsWith("✓") || line.startsWith("✗") ||
            line.startsWith("⚠") || line.startsWith("Working", ignoreCase = true) ||
            line.startsWith("Running", ignoreCase = true) ||
            line.startsWith("Thinking", ignoreCase = true) ||
            line.startsWith("Reading", ignoreCase = true) ||
            line.startsWith("Searching", ignoreCase = true) ||
            line.startsWith("Esc to cancel", ignoreCase = true) ||
            TOOL_CALL_MARKERS.any { line.startsWith(it) }
    }

    private fun looksLikeCode(value: String): Boolean {
        val line = value.trimStart()
        return line.startsWith("diff --git ") ||
            line.startsWith("index ") ||
            line.startsWith("@@") ||
            line.startsWith("+++") ||
            line.startsWith("---") ||
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
