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

    data class ModelPickerOption(
        val index: Int,
        val label: String,
        val current: Boolean = false,
    )

    data class SessionEtaState(
        val remainingSeconds: Long,
        val totalSeconds: Long,
        val description: String,
    ) {
        fun toEta(nowMillis: Long): SessionEta = SessionEta(
            deadlineMillis = nowMillis + remainingSeconds * 1000,
            totalMillis = totalSeconds * 1000,
            description = description,
        )
    }

    data class HookState(
        val active: Boolean,
        val atEpochSeconds: Long,
    ) {
        fun isReady(nowMillis: Long): Boolean? = when {
            !active -> true
            nowMillis - atEpochSeconds * 1000 <= ACTIVE_EVENT_TRUST_MS -> false
            else -> null
        }
    }

    data class SessionEta(
        val deadlineMillis: Long,
        val totalMillis: Long,
        val description: String,
    ) {
        fun remainingMillis(nowMillis: Long): Long = deadlineMillis - nowMillis

        fun fillFraction(nowMillis: Long): Float {
            if (totalMillis <= 0) return 0f
            val elapsed = totalMillis - remainingMillis(nowMillis)
            return (elapsed.toFloat() / totalMillis).coerceIn(0f, 1f)
        }

        fun label(nowMillis: Long): String = formatEtaRemaining(remainingMillis(nowMillis))
    }

    fun formatEtaRemaining(remainingMillis: Long): String = when {
        remainingMillis < 0 -> "+${((-remainingMillis) / 60_000).coerceAtLeast(1)}m"
        remainingMillis < 60_000 -> "<1m"
        else -> {
            val totalMinutes = remainingMillis / 60_000
            if (totalMinutes < 60) "${totalMinutes}m" else "${totalMinutes / 60}h ${totalMinutes % 60}m"
        }
    }

    data class AgentAccuracy(
        val agent: String,
        val ratio: Double,
        val samples: Int,
    )

    fun etaAccuracy(value: String, limit: Int = 20): List<AgentAccuracy> {
        val ratios = mutableMapOf<String, MutableList<Double>>()
        value.lineSequence().forEach { line ->
            val row = runCatching { org.json.JSONObject(line) }.getOrNull() ?: return@forEach
            val estimate = row.optDouble("est_seconds", 0.0)
            val actual = row.optDouble("actual_seconds", 0.0)
            if (estimate <= 0.0 || actual < MIN_ACCURACY_ACTUAL_SECONDS) return@forEach
            val agent = row.optString("agent").ifBlank { "unknown" }
            ratios.getOrPut(agent) { mutableListOf() }.add(actual / estimate)
        }
        return ratios.mapNotNull { (agent, values) ->
            val sample = values.takeLast(limit).sorted()
            if (sample.size < MIN_ACCURACY_SAMPLES) return@mapNotNull null
            val middle = sample.size / 2
            val median = if (sample.size % 2 == 0) {
                (sample[middle - 1] + sample[middle]) / 2
            } else {
                sample[middle]
            }
            AgentAccuracy(agent, median, sample.size)
        }.sortedBy { it.agent }
    }

    fun parseHookStates(value: String): Map<String, HookState> = buildMap {
        value.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 4 || parts[0] != HOOK_STATE_PREFIX) return@forEach
            val name = parts[1].trim()
            val state = parts[2].trim().lowercase()
            val at = parts[3].toLongOrNull() ?: return@forEach
            if (name.isBlank() || state !in HOOK_STATES) return@forEach
            put(name, HookState(active = state == "active", atEpochSeconds = at))
        }
    }

    fun parseSessionEtaStates(value: String): Map<String, SessionEtaState> = buildMap {
        value.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@forEach
            val name = parts[0].trim()
            val remaining = parts[1].toLongOrNull() ?: return@forEach
            val total = parts[2].toLongOrNull() ?: 0L
            if (name.isBlank()) return@forEach
            put(
                name,
                SessionEtaState(
                    remainingSeconds = remaining,
                    totalSeconds = total,
                    description = parts.getOrElse(3) { "" },
                ),
            )
        }
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

    fun containsLink(value: String): Boolean = WEB_LINK.containsMatchIn(value)

    fun leftAligned(value: String): String = value
        .lineSequence()
        .joinToString("\n") { it.trimStart() }
        .trim()

    fun runtimeDetails(value: String): RuntimeDetails {
        val lines = value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        lines.takeLast(RUNTIME_SEARCH_LINES).asReversed().forEach { line ->
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

    fun modelPickerOptions(value: String): List<ModelPickerOption> {
        val lines = value.lines()
        val headerIndex = lines.indexOfLast(::isModelPickerHeader)
        if (headerIndex < 0 || headerIndex < lines.size - MODEL_PICKER_SEARCH_LINES) {
            return emptyList()
        }
        return lines.asSequence()
            .drop(headerIndex + 1)
            .mapNotNull { rawLine ->
                val match = MODEL_PICKER_OPTION.find(rawLine) ?: return@mapNotNull null
                var label = match.groupValues[2].trim()
                val current = label.endsWith(" (current)", ignoreCase = true)
                label = label.removeSuffix(" (current)").removeSuffix(" (default)").trim()
                ModelPickerOption(
                    index = match.groupValues[1].toInt(),
                    label = label,
                    current = current,
                )
            }
            .distinctBy(ModelPickerOption::index)
            .toList()
    }

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

    fun mergeSnapshot(history: String, snapshot: String): String {
        val collected = history.lines().dropLastWhile(String::isBlank)
        val screen = snapshot.lines().dropLastWhile(String::isBlank)
        if (collected.isEmpty()) return screen.joinToString("\n")
        if (screen.isEmpty()) return collected.joinToString("\n")
        val screenStart = findScreenStart(collected, screen)
        if (screenStart != null) {
            return (collected.subList(0, screenStart) + screen)
                .takeLast(MAX_TRANSCRIPT_LINES)
                .joinToString("\n")
        }
        return (collected + screen).takeLast(MAX_TRANSCRIPT_LINES).joinToString("\n")
    }

    private fun findScreenStart(collected: List<String>, screen: List<String>): Int? {
        val historyStart = (collected.size - ALIGNMENT_SEARCH_LINES).coerceAtLeast(0)
        var bestStart = -1
        var bestLength = 0

        screen.indices.forEach { screenIndex ->
            for (historyIndex in historyStart until collected.size) {
                val candidateStart = historyIndex - screenIndex
                if (candidateStart < 0 || collected[historyIndex] != screen[screenIndex]) continue

                var length = 1
                while (historyIndex + length < collected.size &&
                    screenIndex + length < screen.size &&
                    collected[historyIndex + length] == screen[screenIndex + length]
                ) {
                    length++
                }
                val matchingLines = screen.subList(screenIndex, screenIndex + length)
                if (reliableScreenMatch(matchingLines) &&
                    (length > bestLength || length == bestLength && candidateStart > bestStart)
                ) {
                    bestStart = candidateStart
                    bestLength = length
                }
            }
        }
        return bestStart.takeIf { it >= 0 }
    }

    private fun reliableScreenMatch(lines: List<String>): Boolean {
        val distinctive = lines.count { line ->
            val trimmed = line.trim()
            trimmed.length >= MIN_DISTINCTIVE_LINE_LENGTH &&
                !isDivider(trimmed) &&
                !isTerminalChrome(trimmed)
        }
        return lines.size >= MIN_ALIGNMENT_LINES && distinctive >= 1
    }

    fun readerBlocks(
        value: String,
        agent: AgentKind = AgentKind.OTHER,
        knownPrompts: List<String> = emptyList(),
    ): List<ReaderBlock> {
        val blocks = mutableListOf<ReaderBlock>()
        val lines = value.lines()
        val current = StringBuilder()
        var currentKind = ReaderBlockKind.PROSE
        var fencedCode = false
        var fencedLanguage: String? = null
        var userPromptOpen = false
        val knownSignatures = knownPrompts.map(::promptSignature).filter(String::isNotEmpty)
        var matchingKnownPrompt: String? = null
        var lastLineFilledTheRow = false
        val pairedFence = lines.indices
            .filter { lines[it].trim().matches(FENCE_LINE) }
            .chunked(2)
            .filter { it.size == 2 }
            .flatten()
            .toHashSet()

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
            lastLineFilledTheRow = false
            userPromptOpen = false
            matchingKnownPrompt = null
        }

        fun append(kind: ReaderBlockKind, line: String) {
            if (current.isNotEmpty() && currentKind != kind) flush()
            currentKind = kind
            val rejoin = lastLineFilledTheRow &&
                kind != ReaderBlockKind.CODE &&
                !startsNewParagraph(line)
            val joinInsideAddress = current.isNotEmpty() &&
                !line.startsWith(" ") &&
                endsInsideWebAddress(current) &&
                looksLikeAddressContinuation(line)
            if (current.isNotEmpty()) current.append(if (joinInsideAddress) "" else if (rejoin) ' ' else '\n')
            current.append(
                if (kind == ReaderBlockKind.USER_PROMPT) removePromptMarker(line, agent) else line.trimEnd(),
            )
            lastLineFilledTheRow = line.trimEnd().length >= WRAPPED_ROW_LENGTH
            if (kind == ReaderBlockKind.USER_PROMPT) {
                userPromptOpen = true
                val signature = promptSignature(current.toString())
                val known = matchingKnownPrompt
                    ?: knownSignatures
                        .filter { signature.isNotEmpty() && it.startsWith(signature) }
                        .maxByOrNull(String::length)
                        ?.also { matchingKnownPrompt = it }
                if (known != null && signature.length >= known.length) {
                    matchingKnownPrompt = null
                    userPromptOpen = false
                }
            }
        }

        lines.forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (isDivider(trimmed)) {
                if (current.isNotEmpty()) flush()
                lastLineFilledTheRow = false
                return@forEachIndexed
            }
            if (lineIndex in pairedFence && trimmed.startsWith("``")) {
                if (current.isNotEmpty()) flush()
                if (fencedCode) {
                    fencedCode = false
                    currentKind = ReaderBlockKind.PROSE
                } else {
                    fencedCode = true
                    currentKind = ReaderBlockKind.CODE
                    fencedLanguage = trimmed.removePrefix("```").trim().ifBlank { null }
                }
                return@forEachIndexed
            }
            if (trimmed.isBlank()) {
                lastLineFilledTheRow = false
                if (current.isNotEmpty()) {
                    if (currentKind == ReaderBlockKind.USER_PROMPT && userPromptOpen) {
                        current.append('\n')
                    } else if (currentKind == ReaderBlockKind.CODE ||
                        currentKind == ReaderBlockKind.PROGRESS
                    ) {
                        flush()
                    } else {
                        current.append('\n')
                    }
                }
                return@forEachIndexed
            }
            val known = matchingKnownPrompt
            if (known != null && currentKind == ReaderBlockKind.USER_PROMPT) {
                val candidate = promptSignature(current.toString() + " " + line)
                if (known.startsWith(candidate)) {
                    append(ReaderBlockKind.USER_PROMPT, line)
                    return@forEachIndexed
                }
                matchingKnownPrompt = null
            }
            if (currentKind == ReaderBlockKind.USER_PROMPT && userPromptOpen &&
                !line.startsWith("  ") && !lastLineFilledTheRow && current.isNotEmpty()
            ) {
                flush()
            }
            if (currentKind == ReaderBlockKind.USER_PROMPT && looksLikeUserPrompt(line, agent)) flush()
            val kind = when {
                looksLikeUserPrompt(line, agent) -> ReaderBlockKind.USER_PROMPT
                looksLikeProgress(line, agent) -> ReaderBlockKind.PROGRESS
                currentKind == ReaderBlockKind.USER_PROMPT && userPromptOpen &&
                    (line.startsWith("  ") || lastLineFilledTheRow) -> {
                    ReaderBlockKind.USER_PROMPT
                }
                currentKind == ReaderBlockKind.PROGRESS && !line.trimStart().startsWith("•") -> {
                    ReaderBlockKind.PROGRESS
                }
                agent == AgentKind.CLAUDE && line.trimStart().startsWith("\u25cf ") ->
                    ReaderBlockKind.PROSE

                fencedCode || looksLikeCode(line) -> ReaderBlockKind.CODE
                currentKind == ReaderBlockKind.CODE -> {
                    ReaderBlockKind.CODE
                }
                else -> ReaderBlockKind.PROSE
            }
            append(kind, line)
        }
        flush()
        return withoutRepeatedReplies(blocks)
    }

    private fun withoutRepeatedReplies(blocks: List<ReaderBlock>): List<ReaderBlock> {
        var previousReply: String? = null
        return buildList {
            blocks.forEach { block ->
                if (block.kind == ReaderBlockKind.USER_PROMPT) previousReply = null
                if (block.kind == ReaderBlockKind.PROSE) {
                    val key = block.text.replace(WHITESPACE, " ").trim()
                    if (key.length >= MIN_DUPLICATE_REPLY_LENGTH && key == previousReply) {
                        return@forEach
                    }
                    previousReply = key
                }
                add(block)
            }
        }
    }

    fun isWaitingForInput(value: String, agent: AgentKind = AgentKind.OTHER): Boolean {
        val tail = value.lineSequence()
            .map(String::trimStart)
            .filter(String::isNotBlank)
            .toList()
            .takeLast(INPUT_SEARCH_LINES)
        if (tail.any { line -> SPINNER_LINE.containsMatchIn(line) }) return false
        val meaningful = tail.withIndex()
            .filterNot { (_, line) -> isDivider(line) || isTerminalChrome(line) }
            .toList()
        val lastMeaningful = meaningful.lastOrNull()?.index ?: return false
        val latestCompletion = meaningful.indexOfLast { (_, line) ->
            COMPLETION_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
        }
        val latestActivity = meaningful.indexOfLast { (_, line) -> looksLikeActivity(line) }
        val latestPrompt = meaningful.indexOfLast { (_, line) ->
            isPromptMarker(line, agent)
        }
        val latestIdleMarker = meaningful.indexOfLast { (_, line) ->
            IDLE_INPUT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }
        }

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

    fun isModelSwitchConfirmation(value: String): Boolean {
        val lines = value.lineSequence()
        return lines.any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("Switch model?", ignoreCase = true) ||
                trimmed.startsWith("Change effort level?", ignoreCase = true) ||
                trimmed.startsWith("Yes, switch to", ignoreCase = true) ||
                trimmed.contains("asked you to confirm", ignoreCase = true)
        }
    }

    private fun startsNewParagraph(value: String): Boolean {
        if (value.startsWith("  ")) return true
        val line = value.trim()
        return line.isEmpty() || PARAGRAPH_START.containsMatchIn(line)
    }

    private fun isActivityBullet(line: String): Boolean {
        if (!line.startsWith("•")) return false
        val body = line.removePrefix("•").trim()
        return ACTIVITY_DURATION.containsMatchIn(body) ||
            body.endsWith("(completed)") ||
            body.contains("(failed") ||
            ACTIVITY_VERBS.any { verb -> body.startsWith(verb, ignoreCase = true) } ||
            TOOL_CALL_MARKERS.any { marker -> body.startsWith(marker) }
    }

    private fun isDivider(value: String): Boolean {
        val compact = value.filterNot(Char::isWhitespace)
        if (compact.length < 8) return false
        if (compact.all { it in DIVIDER_CHARACTERS }) return true
        if (compact.first() !in DIVIDER_CHARACTERS || compact.last() !in DIVIDER_CHARACTERS) return false
        val rule = compact.count { it in DIVIDER_CHARACTERS }
        return rule >= 8 && compact.length - rule <= DIVIDER_LABEL_LIMIT
    }

    private fun isModelPickerHeader(value: String): Boolean {
        val line = value.trim()
        return line.startsWith("Select Model", ignoreCase = true) ||
            line.startsWith("Select Reasoning", ignoreCase = true)
    }

    private fun looksLikeUserPrompt(value: String, agent: AgentKind): Boolean =
        isPromptMarker(value.trimStart(), agent)

    private fun isPromptMarker(value: String, agent: AgentKind): Boolean {
        val line = value.trimStart()
        return when {
            line.startsWith("❯") -> true
            agent == AgentKind.HAX && isHaxUserPrompt(line) -> true
            agent != AgentKind.HAX && line.startsWith("›") -> true
            line.startsWith("> ") -> true
            else -> false
        } ||
            line.startsWith("User:", ignoreCase = true) ||
            line.startsWith("You:", ignoreCase = true)
    }

    private fun looksLikeAddressContinuation(line: String): Boolean {
        val token = line.trimStart().substringBefore(' ').substringBefore('\t')
        if (token.isEmpty() || isDivider(token.trim())) return false
        return ADDRESS_CONTINUATION.containsMatchIn(token)
    }

    private fun endsInsideWebAddress(value: CharSequence): Boolean {
        var start = value.length - 1
        while (start >= 0 && !value[start].isWhitespace()) start--
        val tail = value.subSequence(start + 1, value.length).toString().lowercase()
        return tail.startsWith("http://") || tail.startsWith("https://") || tail.startsWith("www.")
    }

    private fun promptSignature(value: String): String = value.replace(WHITESPACE, " ").trim()

    private fun removePromptMarker(value: String, agent: AgentKind): String = value.trimStart()
        .removePrefix("❯")
        .removePrefix("›")
        .removePrefix("▌")
        .removePrefix("> ")
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
            isActivityBullet(line) ||
            SPINNER_LINE.containsMatchIn(line) ||
            FINISHED_SPINNER_LINE.matches(line) ||
            line.startsWith("└") ||
            line.startsWith("│") ||
            line.startsWith("… +") ||
            line.startsWith("... +") ||
            line.startsWith("Ran ", ignoreCase = true) ||
            line.startsWith("Explored", ignoreCase = true) ||
            line.startsWith("ctrl + t", ignoreCase = true) ||
            line.startsWith("esc to interrupt", ignoreCase = true) ||
            BACKGROUND_ACTIVITY_LINE.containsMatchIn(line) ||
            (agent == AgentKind.PI && (line.startsWith("→") || line.startsWith("←"))) ||
            (agent == AgentKind.PI && PI_CHROME_LINE.matches(line)) ||
            (agent == AgentKind.PI && PI_TOOL_LINE.containsMatchIn(line)) ||
            (agent == AgentKind.HAX && looksLikeHaxDetail(line)) ||
            (agent == AgentKind.CURSOR && CURSOR_CHROME_LINE.matches(line)) ||
            (agent == AgentKind.CURSOR && CURSOR_TOOL_LINE.containsMatchIn(line)) ||
            (agent == AgentKind.CLAUDE && CLAUDE_COLLAPSIBLE_LINE.matches(line)) ||
                TOOL_CALL_MARKERS.any { line.startsWith(it) }
    }

    private fun isHaxUserPrompt(value: String): Boolean =
        value.startsWith("▌ ") && !HAX_CHROME_LINE.containsMatchIn(value)

    private fun looksLikeHaxDetail(value: String): Boolean =
        HAX_TOOL_LINE.containsMatchIn(value) ||
            HAX_TASK_LINE.containsMatchIn(value) ||
            HAX_STATUS_LINE.containsMatchIn(value) ||
            HAX_CHROME_LINE.containsMatchIn(value) ||
            value.startsWith("[paused ") ||
            value.startsWith("› [")

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
        val line = value.trim().dropWhile { !it.isLetterOrDigit() && it != '/' }.lowercase()
        return STATUS_BAR.containsMatchIn(value) ||
            SLASH_HINT.matches(line) ||
            line.startsWith("gpt-") ||
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
            line.matches(ASSIGNMENT_LINE) ||
            line.endsWith(" {") ||
            line.endsWith("}") ||
            line.endsWith(";")
    }

    private const val COMPOSER_SEARCH_LINES = 18
    private const val MODEL_PICKER_SEARCH_LINES = 80
    private const val RUNTIME_SEARCH_LINES = 12
    private const val ALIGNMENT_SEARCH_LINES = 512
    private const val MIN_ALIGNMENT_LINES = 3
    private const val MIN_DISTINCTIVE_LINE_LENGTH = 3
    private const val MIN_DUPLICATE_REPLY_LENGTH = 80
    private const val MAX_TRANSCRIPT_LINES = 3000
    private const val INPUT_SEARCH_LINES = 24
    private const val DIVIDER_CHARACTERS = "─━═╌╍-_▔▁"
    private const val DIVIDER_LABEL_LIMIT = 32
    private const val WRAPPED_ROW_LENGTH = 55
    private val PARAGRAPH_START = Regex("^(?:[-*•·>❯›#|+]|\\d+[.)]\\s)")
    private val ASSIGNMENT_LINE = Regex("[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*\\S.*")
    private val CODE_LINE = Regex("(?:fun|class|interface|object|const|val|var)\\b.*(?:[({=]|\\s*$)")
    private val NUMBERED_DIFF_LINE = Regex("\\d+\\s+[+-](?:\\s|$).*")
    private val FENCE_LINE = Regex("^```[A-Za-z0-9_+.-]*$")
    private const val HOOK_STATE_PREFIX = "@state"
    private val HOOK_STATES = setOf("active", "done")
    private const val MIN_ACCURACY_ACTUAL_SECONDS = 120.0
    private const val MIN_ACCURACY_SAMPLES = 3
    private const val ACTIVE_EVENT_TRUST_MS = 30 * 60 * 1000L
    private val ADDRESS_CONTINUATION = Regex("[/\\\\?=&#%@:]")
    private val DIFF_PATH = Regex("diff --git a/\\S+ b/(\\S+)")
    private val FILE_PATH = Regex("(?:^|\\n)(?:\\+\\+\\+ b/|File: )([^\\s]+)")
    private const val RUNTIME_GAP = "[\\s\u26a1·•|:]+"
    private val CODEX_RUNTIME = Regex(
        "(?i)\\b((?:gpt|o)[a-z0-9.-]+)$RUNTIME_GAP(none|minimal|low|medium|high|xhigh|max|ultra)\\b",
    )
    private val CLAUDE_RUNTIME = Regex(
        "(?i)\\b((?:claude\\s+)?(?:opus|sonnet|haiku|fable)(?:\\s+[0-9]+(?:[.][0-9]+)*)?)$RUNTIME_GAP" +
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
    private val MODEL_PICKER_OPTION = Regex("^\\s*(?:[›>]\\s+)?([1-9])\\.\\s+(.+?)\\s*$")
    private val STATUS_BAR = Regex("\\d+MB\\s+\\d{1,2}:\\d{2}\\s*$")
    private val SLASH_HINT = Regex("/[a-z-]{1,12}")
    private const val SPINNER_MARKERS = "●·✳✻✶✽✢✷∗✴*"
    private val SPINNER_LINE = Regex(
        "(?:^\\s*[$SPINNER_MARKERS]\\s+\\S+…\\s*\\(\\s*\\d)|(?i)esc to interrupt",
    )
    private val FINISHED_SPINNER_LINE = Regex(
        "(?i)^\\s*[$SPINNER_MARKERS]\\s+\\S+\\s+for\\s+" +
            "\\d+(?:\\.\\d+)?\\s*(?:ms|s|m|h)(?:\\s+\\d+(?:\\.\\d+)?\\s*(?:ms|s|m|h))*" +
            "(?:\\s+·\\s+done\\b.*)?\\s*$",
    )
    private val DURATION_LINE = Regex(
        "(?i)^took\\s+\\d+(?:\\.\\d+)?\\s*(?:ms|s|m|h)(?:\\s+\\d+(?:\\.\\d+)?\\s*(?:ms|s|m|h))*\\s*$",
    )

    private val PI_CHROME_LINE = Regex(
        """(?i)^\s*(?:\$\s.*|took\s.*|describe what to write.*|press shift\+r.*|qwertyuiop|asdfghjkl|zxcvbnm|shift\+.*|ctrl\+.*)$""",
    )
    private val CURSOR_CHROME_LINE = Regex(
        """(?i)^\s*(?:cursor agent|v\d{4}\.\d\d.*|tip:.*|show (?:code|activity).*|auto[ -].*|qwertyuiop|asdfghjkl|zxcvbnm|to me.*)$""",
    )
    private val CURSOR_TOOL_LINE = Regex(
        "(?i)^\\s*(?:show (?:code|activity)\\b|code[ -]+\\d+ lines?[ -]|grepped\\b|" +
            "read \\S+\\.(?:ts|tsx|js|cjs|mjs|json|md)|edited \\b|to-do\\b|\\.{2} \\d+ earlier items hidden)",
    )

    private val CLAUDE_COLLAPSIBLE_LINE = Regex(
        "(?i)^\\s*(?:show (?:code|activity)\\b|hide (?:code|activity)\\b|" +
            "(?:code|activity)\\s*\\u00b7\\s*\\d+ lines?\\b)",
    )
    private val PI_TOOL_LINE = Regex(
        """(?i)^\s*(?:\.{3} [(]|\d+:\s+\S|elapsed \d|\u2191\S|[(]openrouter[)]|ctrl\+o to expand)"""
    )
    private val BACKGROUND_ACTIVITY_LINE = Regex(
        "(?i)^●\\s+Background\\s+(?:command|task)\\b",
    )
    private val WHITESPACE = Regex("\\s+")
    private val WEB_LINK = Regex("(?i)https?://")
    private val ACTIVITY_DURATION = Regex("·\\s*(?:\\d+(?:\\.\\d+)?\\s*(?:ms|s|m|h)\\s*)+$")
    private val ACTIVITY_VERBS = listOf(
        "Ran ", "Read ", "Reading ", "Explored", "Exploring", "Searched", "Searching",
        "Listing ", "Listed ", "Called ", "Running ", "Edited ", "Wrote ", "Applied ",
        "Updated Plan", "Waiting ", "Fetched ", "Fetching ",
    )
    private val TOOL_CALL_MARKERS = listOf(
        "Read(", "Edit(", "Write(", "Bash(", "Glob(", "Grep(", "Task(", "WebFetch(",
    )
    private val HAX_TOOL_LINE = Regex("^\\[(?:bash|read|edit|write|task_wait)](?:\\s|$)")
    private val HAX_TASK_LINE = Regex("^\\[task(?:_| )")
    private val HAX_STATUS_LINE = Regex(
        "^(?:\\d+(?:\\.\\d+)?\\s*(?:ms|s|m|h)\\s*)+·\\s*\\S+\\s*/\\s*\\S+\\s*" +
            "\\(\\d+%\\)(?:\\s*·.*)?$",
    )
    private val HAX_CHROME_LINE = Regex("^▌\\s+(?:hax(?:\\s|$)|ctrl-[a-z])", RegexOption.IGNORE_CASE)
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
