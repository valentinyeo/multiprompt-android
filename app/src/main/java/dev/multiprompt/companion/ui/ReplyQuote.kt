package dev.multiprompt.companion.ui

internal object ReplyQuote {
    private const val PREVIEW_LIMIT = 64
    private const val MORE = "… more"
    private const val ELLIPSIS = "…"
    private val COMMAND = Regex("^/[A-Za-z0-9_:.@-]{1,64}$")

    data class Reply(val quoted: String, val body: String) {
        val reference: Reference get() = ReplyQuote.reference(quoted)
    }

    data class Reference(val runs: List<InlineRun>, val longer: Boolean) {
        val text: String get() = runs.joinToString("") { it.text }
    }

    fun parse(message: String): Reply? {
        val lines = message.lines()
        val head = lines.firstOrNull().orEmpty()
        val command = head.substringBefore(' ').takeIf { COMMAND.matches(it) }
        val afterCommand = if (command == null) "" else head.removePrefix(command).trimStart()
        val inline = afterCommand.isNotEmpty() && !quoted(afterCommand) && lines.getOrNull(1)?.let(::quoted) == true
        val rest = when {
            command == null -> lines
            inline -> lines.drop(1)
            head == command -> lines.drop(1).dropWhile { it.isBlank() }
            else -> listOf(head.removePrefix(command).trimStart()) + lines.drop(1)
        }
        val end = rest.indexOfFirst { !quoted(it) }
        if (end == 0 || end > 0 && !rest[end].isBlank()) return null
        val boundary = if (end < 0) rest.size else end
        val block = rest.take(boundary).dropLastWhile { it.isBlank() }
        if (block.all { it.removePrefix(">").isBlank() }) return null
        val body = rest.drop(boundary).dropWhile { it.isBlank() }.joinToString("\n")
        val text = when {
            inline -> listOf(head, body).filter { it.isNotBlank() }.joinToString("\n")
            body.isBlank() -> command ?: return null
            command == null -> body
            else -> "$command $body"
        }
        return Reply(block.joinToString("\n"), text)
    }

    private fun quoted(line: String): Boolean = line == ">" || line.startsWith("> ")

    fun sourceIndex(messages: List<String>, before: Int, quoted: String): Int? =
        (0 until before.coerceIn(0, messages.size)).lastOrNull { quote(messages[it]) == quoted }

    fun quote(message: String): String {
        val lines = message.lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            .map { it.trimEnd() }
        val visible = if (lines.size > 10) lines.take(10) + ELLIPSIS else lines
        return visible.joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    }

    fun compose(quoted: String?, message: String): String = when {
        quoted.isNullOrBlank() -> message
        message.isBlank() -> ""
        else -> {
            val head = message.lineSequence().first()
            if (command(head) == null) quote(quoted) + "\n\n" + message
            else {
                val tail = message.lineSequence().drop(1).dropWhile { it.isBlank() }.joinToString("\n")
                head + "\n" + quote(quoted) + if (tail.isBlank()) "" else "\n\n" + tail
            }
        }
    }

    private fun command(head: String): String? =
        head.substringBefore(' ').takeIf { COMMAND.matches(it) }

    fun preview(message: String, limit: Int = PREVIEW_LIMIT): String = cut(
        message.lineSequence().filter { it.isNotBlank() }.take(2).joinToString(" "),
        limit,
    )

    fun reference(quoted: String, limit: Int = PREVIEW_LIMIT): Reference {
        val lines = quoted.lines().map { it.removePrefix(">").removePrefix(" ") }
            .dropLastWhile { it.isBlank() }
            .let { if (it.lastOrNull()?.trim() == ELLIPSIS) it.dropLast(1) else it }
        val blocks = MarkdownParser.parse(lines.joinToString("\n"))
        val first = blocks.indexOfFirst { MarkdownParser.blockText(it).isNotBlank() }
        if (first < 0) return Reference(emptyList(), false)
        val more = blocks.drop(first + 1).any { MarkdownParser.blockText(it).isNotBlank() }
        val budget = (limit - if (more) MORE.length else 0).coerceAtLeast(16)
        val (runs, truncated) = cutRuns(blockRuns(blocks[first]), budget)
        val marker = when {
            more -> MORE
            truncated -> ELLIPSIS
            else -> ""
        }
        return Reference(if (marker.isEmpty()) runs else runs + InlineRun(marker), more || truncated)
    }

    private fun blockRuns(block: MdBlock): List<InlineRun> = when (block) {
        is MdBlock.Paragraph -> block.runs
        is MdBlock.Heading -> block.runs
        is MdBlock.Quote -> block.runs
        is MdBlock.ListItem -> listOf(InlineRun(block.marker + " ")) + block.runs
        is MdBlock.Code -> listOf(InlineRun(block.text.trim().lineSequence().firstOrNull().orEmpty()))
        is MdBlock.Table -> block.header.firstOrNull().orEmpty()
        MdBlock.Rule -> emptyList()
    }

    private fun cutRuns(runs: List<InlineRun>, limit: Int): Pair<List<InlineRun>, Boolean> {
        if (runs.sumOf { it.text.length } <= limit) return runs to false
        val out = ArrayList<InlineRun>()
        var used = 0
        for (run in runs) {
            val room = limit - used
            if (run.text.length <= room) {
                out += run
                used += run.text.length
                continue
            }
            val head = wholeWords(run.text.take(room))
            if (head.isNotEmpty()) out += run.copy(text = head)
            break
        }
        return out to true
    }

    private fun cut(text: String, limit: Int): String {
        val flat = text.trim()
        return if (flat.length <= limit) flat else wholeWords(flat.take(limit)) + ELLIPSIS
    }

    private fun wholeWords(text: String): String {
        val space = text.lastIndexOf(' ')
        return (if (space > 0) text.substring(0, space) else text).trimEnd()
    }
}
