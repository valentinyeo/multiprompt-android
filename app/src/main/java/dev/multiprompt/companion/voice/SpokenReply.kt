package dev.multiprompt.companion.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpokenReplyStatus {
    INITIALIZING,
    READY,
    SPEAKING,
    FAILED,
}

data class SpokenReplyState(
    val status: SpokenReplyStatus = SpokenReplyStatus.INITIALIZING,
    val error: String? = null,
)

class SpokenReply(context: Context) : AutoCloseable {
    private val _state = MutableStateFlow(SpokenReplyState())
    val state: StateFlow<SpokenReplyState> = _state.asStateFlow()
    private var engine: TextToSpeech? = null
    @Volatile
    private var finalUtteranceId: String? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val activeEngine = engine
            if (status != TextToSpeech.SUCCESS || activeEngine == null) {
                _state.value = SpokenReplyState(
                    status = SpokenReplyStatus.FAILED,
                    error = "Speech output is unavailable",
                )
                return@TextToSpeech
            }
            val language = activeEngine.setLanguage(Locale.ENGLISH)
            if (language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                _state.value = SpokenReplyState(
                    status = SpokenReplyStatus.FAILED,
                    error = "English speech output is unavailable",
                )
                return@TextToSpeech
            }
            activeEngine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            activeEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _state.value = SpokenReplyState(SpokenReplyStatus.SPEAKING)
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == finalUtteranceId) {
                        finalUtteranceId = null
                        _state.value = SpokenReplyState(SpokenReplyStatus.READY)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    fail()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    fail()
                }
            })
            _state.value = SpokenReplyState(SpokenReplyStatus.READY)
        }
    }

    fun speak(text: String): Boolean {
        val activeEngine = engine ?: return false
        if (_state.value.status != SpokenReplyStatus.READY) return false
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) return false
        val batchId = UUID.randomUUID().toString()
        finalUtteranceId = "$batchId:${chunks.lastIndex}"
        _state.value = SpokenReplyState(SpokenReplyStatus.SPEAKING)
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = activeEngine.speak(chunk, queueMode, null, "$batchId:$index")
            if (result == TextToSpeech.ERROR) {
                fail()
                return false
            }
        }
        return true
    }

    fun stop() {
        finalUtteranceId = null
        engine?.stop()
        if (_state.value.status != SpokenReplyStatus.FAILED) {
            _state.value = SpokenReplyState(SpokenReplyStatus.READY)
        }
    }

    private fun fail() {
        finalUtteranceId = null
        engine?.stop()
        _state.value = SpokenReplyState(
            status = SpokenReplyStatus.FAILED,
            error = "The reply could not be spoken",
        )
    }

    override fun close() {
        finalUtteranceId = null
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

internal fun speechChunks(text: String, maximumLength: Int): List<String> {
    require(maximumLength > 0)
    val remaining = text.trim()
    if (remaining.isBlank()) return emptyList()
    return buildList {
        var start = 0
        while (start < remaining.length) {
            val hardEnd = (start + maximumLength).coerceAtMost(remaining.length)
            val end = if (hardEnd == remaining.length) {
                hardEnd
            } else {
                remaining.lastIndexOfAny(charArrayOf('\n', '.', '?', '!', ' '), hardEnd - 1)
                    .takeIf { it >= start + maximumLength / 2 }
                    ?.plus(1)
                    ?: hardEnd
            }
            remaining.substring(start, end).trim().takeIf(String::isNotBlank)?.let(::add)
            start = end
        }
    }
}
