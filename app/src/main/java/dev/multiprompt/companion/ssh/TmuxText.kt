package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.AgentKind

object TmuxText {
    data class RuntimeDetails(
        val model: String? = null,
        val effort: String? = null,
    ) {
        val label: String?
            get() = model?.let { name -> listOfNotNull(name, effort).joinToString(" · ") }
    }

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

    /** Reads model/effort metadata from Codex and Claude TUI or cloud-session status lines. */
    fun runtimeDetails(value: String): RuntimeDetails {
        value.lineSequence().toList().asReversed().forEach { rawLine ->
            val line = rawLine.trim()
            CODEX_RUNTIME.find(line)?.let { match ->
                return RuntimeDetails(match.groupValues[1], match.groupValues[2].lowercase().ifBlank { null })
            }
            CLAUDE_RUNTIME.find(line)?.let { match ->
                return RuntimeDetails(match.groupValues[1], match.groupValues[2].lowercase().ifBlank { null })
            }
            CODEX_MODEL_ONLY.find(line)?.let { match ->
                return RuntimeDetails(match.groupValues[1])
            }
            CLAUDE_MODEL_ONLY.find(line)?.let { match ->
                return RuntimeDetails(match.groupValues[1].trim())
            }
            CLAUDE_ALIAS_ONLY.find(line)?.let { match ->
                return RuntimeDetails(match.groupValues[1].trim())
            }
        }
        return RuntimeDetails()
    }

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
        var promptMayContinue = false

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
            currentKind = ReaderBlockKind.PROSE
            fencedLanguage = null
            promptMayContinue = false
        }

        fun append(kind: ReaderBlockKind, line: String) {
            if (current.isNotEmpty() && currentKind != kind) flush()
            currentKind = kind
            if (current.isNotEmpty()) current.append('\n')
            current.append(
                if (kind == ReaderBlockKind.USER_PROMPT) removePromptMarker(line, agent) else line.trimEnd(),
            )
            if (kind == ReaderBlockKind.USER_PROMPT) {
                // A terminal-wrapped prompt usually fills the available row. A short prompt
                // followed by ordinary prose is a completed prompt/response pair instead.
                promptMayContinue = line.trim().length >= PROMPT_WRAP_THRESHOLD
            }
        }

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (isDivider(trimmed)) {
                if (current.isNotEmpty()) flush()
                return@forEach
            }
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
                    if (currentKind == ReaderBlockKind.USER_PROMPT ||
                        currentKind == ReaderBlockKind.CODE ||
                        (agent == AgentKind.CODEX && currentKind == ReaderBlockKind.PROGRESS)
                    ) {
                        flush()
                    } else {
                        current.append('\n')
                    }
                }
                return@forEach
            }
            val kind = when {
                looksLikeUserPrompt(line, agent) -> ReaderBlockKind.USER_PROMPT
                looksLikeProgress(line, agent) -> ReaderBlockKind.PROGRESS
                currentKind == ReaderBlockKind.USER_PROMPT && promptMayContinue -> {
                    // Codex and Claude wrap a submitted prompt across terminal rows but only
                    // draw the prompt marker on the first row. Keep those wrapped rows in the
                    // same user bubble; an empty row or activity marker ends the prompt.
                    ReaderBlockKind.USER_PROMPT
                }
                fencedCode || looksLikeCode(line) -> ReaderBlockKind.CODE
                currentKind == ReaderBlockKind.CODE -> {
                    // Code output commonly contains continuation lines that do not have a
                    // reliable syntax marker. Keep the contiguous section together until a
                    // blank row or a new semantic marker appears.
                    ReaderBlockKind.CODE
                }
                agent == AgentKind.CODEX && currentKind == ReaderBlockKind.PROGRESS -> {
                    // Agent TUIs render command output as a marker followed by unmarked wrapped
                    // lines. Keep that output in one activity section until a blank line or a
                    // new semantic block.
                    ReaderBlockKind.PROGRESS
                }
                else -> ReaderBlockKind.PROSE
            }
            append(kind, line)
        }
        flush()
        return blocks
    }

    /**
     * Best-effort Android fallback for ZigShell's agent DONE event.
     *
     * The desktop gets an authoritative lifecycle hook. Android only has a pane
     * snapshot, so an old prompt in the scrollback must never be enough to call a
     * session idle: any meaningful line after the latest prompt means the agent
     * may still be working. Only an explicit idle/completion marker at the end of
     * the current terminal state is accepted.
     */
    fun isWaitingForInput(value: String): Boolean {
        val tail = value.lineSequence()
            .map(String::trimStart)
            .filter(String::isNotBlank)
            .toList()
            .takeLast(INPUT_SEARCH_LINES)
        val meaningful = tail.withIndex()
            .filterNot { (_, line) -> isDivider(line) || isTerminalChrome(line) }
            .toList()
        val lastMeaningful = meaningful.lastOrNull()?.index ?: return false
        val latestCompletion = meaningful.indexOfLast { (_, line) ->
            COMPLETION_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
        }
        val latestActivity = meaningful.indexOfLast { (_, line) -> looksLikeActivity(line) }
        val latestPrompt = meaningful.indexOfLast { (_, line) ->
            line.startsWith("❯") || line.startsWith("›")
        }
        val latestIdleMarker = meaningful.indexOfLast { (_, line) ->
            IDLE_INPUT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
        }

        // A visible activity marker after the latest prompt means the agent is
        // still working, even if a stale completion/status line was painted
        // below it. The desktop does not turn this into a blue dot.
        val activityIndex = meaningful.getOrNull(latestActivity)?.index ?: -1
        val promptIndex = meaningful.getOrNull(latestPrompt)?.index ?: -1
        if (latestActivity >= 0 && (latestPrompt < 0 || activityIndex > promptIndex)) return false

        return when {
            latestIdleMarker >= 0 && meaningful[latestIdleMarker].index == lastMeaningful -> true
            latestCompletion >= 0 && meaningful[latestCompletion].index == lastMeaningful -> true
            latestPrompt >= 0 && meaningful[latestPrompt].index == lastMeaningful -> true
            else -> false
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
            line.startsWith("•") ||
            line.startsWith("└") ||
            line.startsWith("│") ||
            line.startsWith("… +") ||
            line.startsWith("... +") ||
            line.startsWith("Ran ", ignoreCase = true) ||
            line.startsWith("Explored", ignoreCase = true) ||
            line.startsWith("ctrl + t", ignoreCase = true) ||
            line.startsWith("esc to interrupt", ignoreCase = true) ||
            (agent == AgentKind.PI && (line.startsWith("→") || line.startsWith("←"))) ||
                TOOL_CALL_MARKERS.any { line.startsWith(it) }
    }

    private fun looksLikeActivity(value: String): Boolean {
        val line = value.trimStart()
        return line.startsWith("Working", ignoreCase = true) ||
            line.startsWith("Running", ignoreCase = true) ||
            line.startsWith("Thinking", ignoreCase = true) ||
            line.startsWith("Reading", ignoreCase = true) ||
            line.startsWith("Searching", ignoreCase = true) ||
            line.startsWith("Esc to cancel", ignoreCase = true) ||
            line.startsWith("Tool:", ignoreCase = true) ||
            line.startsWith("•") ||
            line.startsWith("⏺") ||
            line.startsWith("⎿") ||
            line.startsWith("└") ||
            line.startsWith("│")
    }

    private fun isTerminalChrome(value: String): Boolean {
        val line = value.trimStart().lowercase()
        return line.startsWith("gpt-") ||
            line.startsWith("opus ") ||
            line.startsWith("sonnet ") ||
            line.startsWith("haiku ") ||
            line.startsWith("bypass permissions") ||
            line.startsWith("shift+tab to cycle")
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
    private const val PROMPT_WRAP_THRESHOLD = 64
    private const val DIVIDER_CHARACTERS = "─━═╌╍-_▔▁"
    private val CODE_LINE = Regex("(?:fun|class|interface|object|const|val|var)\\b.*(?:[({=]|\\s*$)")
    private val NUMBERED_DIFF_LINE = Regex("\\d+\\s+[+-](?:\\s|$).*")
    private val DIFF_PATH = Regex("diff --git a/\\S+ b/(\\S+)")
    private val FILE_PATH = Regex("(?:^|\\n)(?:\\+\\+\\+ b/|File: )([^\\s]+)")
    private val CODEX_RUNTIME = Regex(
        "(?i)\\b((?:gpt|o)[a-z0-9.-]+)\\s+(none|minimal|low|medium|high|xhigh|max|ultra)\\b",
    )
    private val CLAUDE_RUNTIME = Regex(
        "(?i)\\b((?:claude\\s+)?(?:opus|sonnet|haiku|fable)(?:\\s+[0-9]+(?:[.][0-9]+)*)?)\\s+" +
            "(?:(?:effort|reasoning)[: ]+)?(low|medium|high|xhigh|max|ultracode|auto)\\b",
    )
    private val CODEX_MODEL_ONLY = Regex(
        "(?i)\\b(?:model|current model|active model|selected model)\\s*[:=]\\s*" +
            "((?:gpt|o)[a-z0-9.-]+)\\b",
    )
    private val CLAUDE_MODEL_ONLY = Regex(
        "(?i)\\b(?:model|current model|active model|selected model)\\s*[:=]\\s*" +
            "((?:claude[- ]+)?(?:opus|sonnet|haiku|fable)(?:[- ]+[0-9]+(?:[.-][0-9]+)*)?)\\b",
    )
    private val CLAUDE_ALIAS_ONLY = Regex(
        "(?i)^(?:claude\\s+)?(opus|sonnet|haiku|fable)(?:[- ]+[0-9]+(?:[.-][0-9]+)*)?$",
    )
    private val TOOL_CALL_MARKERS = listOf(
        "Read(", "Edit(", "Write(", "Bash(", "Glob(", "Grep(", "Task(", "WebFetch(",
    )
    private val IDLE_INPUT_MARKERS = listOf(
        "new task? /clear to save",
        "press up to edit queued messages",
        "do you want to proceed?",
        "would you like to proceed?",
    )
    private val COMPLETION_MARKERS = listOf(
        "worked for ",
        "session complete",
        "task complete",
    )
}
