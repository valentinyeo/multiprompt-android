package dev.multiprompt.companion.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dev.multiprompt.companion.security.SecretStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

enum class DictationStatus {
    IDLE,
    CONNECTING,
    LISTENING,
    FINISHING,
    FAILED,
}

data class DictationState(
    val status: DictationStatus = DictationStatus.IDLE,
    val transcript: String = "",
    val configured: Boolean = false,
    val error: String? = null,
)

class DeepgramDictation(
    context: Context,
    private val secrets: SecretStore,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val finalSegments = mutableListOf<String>()
    private val _state = MutableStateFlow(
        DictationState(configured = secrets.get(API_KEY_SECRET_ID) != null),
    )
    val state: StateFlow<DictationState> = _state.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var recorder: AudioRecord? = null
    private var recordingJob: Job? = null
    private var finishingJob: Job? = null

    fun saveApiKey(value: String): Boolean {
        val key = value.trim()
        if (key.isBlank() || key.length > MAX_API_KEY_CHARACTERS) return false
        secrets.put(API_KEY_SECRET_ID, key.toByteArray(Charsets.UTF_8))
        _state.update { it.copy(configured = true, error = null) }
        return true
    }

    fun removeApiKey() {
        stop()
        secrets.remove(API_KEY_SECRET_ID)
        _state.value = DictationState(configured = false)
    }

    @Synchronized
    fun start() {
        if (_state.value.status in ACTIVE_STATUSES) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail("Microphone permission is required")
            return
        }
        val apiKey = secrets.get(API_KEY_SECRET_ID)
            ?.toString(Charsets.UTF_8)
            ?.takeIf(String::isNotBlank)
            ?: run {
                _state.value = DictationState(configured = false, error = "Add a Deepgram API key")
                return
            }

        finishingJob?.cancel()
        finalSegments.clear()
        _state.value = DictationState(
            status = DictationStatus.CONNECTING,
            configured = true,
        )
        val request = Request.Builder()
            .url(DEEPGRAM_LISTEN_URL)
            .header("Authorization", "Token $apiKey")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    @Synchronized
    fun stop() {
        val activeSocket = socket ?: return
        stopRecorder()
        _state.update { it.copy(status = DictationStatus.FINISHING) }
        activeSocket.send("""{"type":"Finalize"}""")
        finishingJob?.cancel()
        finishingJob = scope.launch {
            delay(FINAL_RESULT_WAIT_MS)
            activeSocket.send("""{"type":"CloseStream"}""")
            activeSocket.close(1000, "Dictation complete")
        }
    }

    private fun startRecorder(activeSocket: WebSocket) {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) {
                fail("The microphone could not be initialized")
                activeSocket.cancel()
                return@launch
            }
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, AUDIO_BUFFER_BYTES),
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                fail("The microphone could not be initialized")
                activeSocket.cancel()
                return@launch
            }
            recorder = audioRecord
            audioRecord.startRecording()
            _state.update { it.copy(status = DictationStatus.LISTENING, error = null) }
            val buffer = ByteArray(maxOf(minimum, AUDIO_BUFFER_BYTES))
            try {
                while (isActive && recorder === audioRecord) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count > 0 && !activeSocket.send(buffer.toByteString(0, count))) break
                }
            } finally {
                if (recorder === audioRecord) recorder = null
                runCatching { audioRecord.stop() }
                audioRecord.release()
            }
        }
    }

    private fun acceptTranscript(result: DeepgramTranscript) {
        synchronized(finalSegments) {
            if (result.isFinal) finalSegments += result.text
            val finalText = finalSegments.joinToString(" ")
            val combined = if (result.isFinal) {
                finalText
            } else {
                listOf(finalText, result.text).filter(String::isNotBlank).joinToString(" ")
            }
            _state.update { it.copy(transcript = combined) }
        }
    }

    private fun stopRecorder() {
        val activeRecorder = recorder
        recorder = null
        runCatching { activeRecorder?.stop() }
        recordingJob?.cancel()
        recordingJob = null
    }

    private fun fail(message: String) {
        stopRecorder()
        socket = null
        _state.update { it.copy(status = DictationStatus.FAILED, error = message) }
    }

    override fun close() {
        finishingJob?.cancel()
        stopRecorder()
        socket?.cancel()
        socket = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket === webSocket) startRecorder(webSocket) else webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            DeepgramTranscriptParser.parse(text)?.let(::acceptTranscript)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            stopRecorder()
            socket = null
            _state.update { it.copy(status = DictationStatus.IDLE) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            val message = when (response?.code) {
                401, 403 -> "Deepgram rejected the API key"
                else -> "Dictation connection failed"
            }
            fail(message)
        }
    }

    private companion object {
        const val API_KEY_SECRET_ID = "deepgram_api_key"
        const val MAX_API_KEY_CHARACTERS = 512
        const val SAMPLE_RATE = 16_000
        const val AUDIO_BUFFER_BYTES = 3_200
        const val FINAL_RESULT_WAIT_MS = 1_200L
        const val DEEPGRAM_LISTEN_URL =
            "wss://api.deepgram.com/v1/listen" +
                "?model=nova-3&language=multi&smart_format=true&punctuate=true" +
                "&interim_results=true&endpointing=300&encoding=linear16" +
                "&sample_rate=$SAMPLE_RATE&channels=1"
        val ACTIVE_STATUSES = setOf(
            DictationStatus.CONNECTING,
            DictationStatus.LISTENING,
            DictationStatus.FINISHING,
        )
    }
}
