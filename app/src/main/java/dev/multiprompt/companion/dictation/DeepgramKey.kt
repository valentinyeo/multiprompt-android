package dev.multiprompt.companion.dictation

object DeepgramKey {
    enum class Check { ACCEPTED, REJECTED, UNKNOWN }

    private val HEX_KEY = Regex("(?<![0-9A-Fa-f])[0-9A-Fa-f]{40}(?![0-9A-Fa-f])")
    private const val MIN_CHARACTERS = 32
    private const val MAX_CHARACTERS = 512

    fun normalize(raw: String): String? {
        HEX_KEY.find(raw)?.let { return it.value.lowercase() }
        if (raw.trim().any(Char::isWhitespace)) return null
        val cleaned = raw.filter { it in '!'..'~' }.trim('"', '\'')
        return cleaned.takeIf { it.length in MIN_CHARACTERS..MAX_CHARACTERS }
    }

    fun classify(httpCode: Int?): Check = when {
        httpCode == 401 || httpCode == 403 -> Check.REJECTED
        httpCode != null && httpCode < 500 && httpCode != 429 -> Check.ACCEPTED
        else -> Check.UNKNOWN
    }
}
