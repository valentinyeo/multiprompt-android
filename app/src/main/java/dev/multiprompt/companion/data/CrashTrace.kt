package dev.multiprompt.companion.data

object CrashTrace {
    private const val MAX_MESSAGE_CHARS = 200

    private val frame = Regex("""at [^\s()]+\([^()]+\)""")
    private val header = Regex("""((?:Caused by: |Suppressed: )?)([A-Za-z0-9_.$]+(?:Exception|Error|Throwable))(?:: (.*))?""")
    private val message = Regex("""[A-Za-z0-9 .,:()=_\-/'+]{1,$MAX_MESSAGE_CHARS}""")

    fun safeMessage(raw: String?): String? {
        val text = raw?.trim().orEmpty().trimEnd { it.isHighSurrogate() }
        return text.takeIf { message.matches(it) }?.take(MAX_MESSAGE_CHARS)
    }

    fun isHeader(line: String): Boolean = header.matches(line.trim())

    fun safeLine(raw: String): String? {
        val line = raw.trim()
        if (frame.matches(line)) return line
        val parts = header.matchEntire(line) ?: return null
        val type = parts.groupValues[1] + parts.groupValues[2]
        val text = safeMessage(parts.groupValues[3]) ?: return type
        return "$type: $text"
    }

    fun safeLines(raw: String): List<String> = raw.lineSequence().mapNotNull { safeLine(it) }.toList()
}
