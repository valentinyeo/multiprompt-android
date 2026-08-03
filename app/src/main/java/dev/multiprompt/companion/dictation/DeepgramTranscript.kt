package dev.multiprompt.companion.dictation

import org.json.JSONObject

data class DeepgramTranscript(
    val text: String,
    val isFinal: Boolean,
)

object DeepgramTranscriptParser {
    fun parse(message: String): DeepgramTranscript? = runCatching {
        val root = JSONObject(message)
        if (root.optString("type") != "Results") return null
        val alternative = root
            .optJSONObject("channel")
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
            ?: return null
        val text = alternative.optString("transcript").trim()
        if (text.isBlank()) return null
        DeepgramTranscript(
            text = text,
            isFinal = root.optBoolean("is_final", false),
        )
    }.getOrNull()
}
