package dev.multiprompt.companion.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dev.multiprompt.companion.security.SecretStore
import java.io.ByteArrayOutputStream
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
    private val audioLock = Any()
    private val pendingAudio = ByteArrayOutputStream()
    private var socketOpen = false
    private var finalizeWhenOpen = false
    private var closeWhenOpen = false
    // Set while a deliberate stop() teardown is in flight so a failure delivered during
    // that teardown does not surface as an error.
    @Volatile
    private var stopping = false

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
        stopping = false
        synchronized(audioLock) {
            pendingAudio.reset()
            socketOpen = false
            finalizeWhenOpen = false
            closeWhenOpen = false
        }
        _state.value = DictationState(
            status = DictationStatus.CONNECTING,
            configured = true,
        )
        val request = Request.Builder()
            .url(DEEPGRAM_LISTEN_URL)
            .header("Authorization", "Token $apiKey")
            .build()
        // Start recording immediately around the websocket handshake. Audio is buffered until
        // Deepgram is ready, so the first words are not lost during network setup.
        socket = client.newWebSocket(request, Listener())
        startRecorder()
    }

    @Synchronized
    fun stop() {
        val activeSocket = socket ?: return
        stopping = true
        stopRecorder()
        _state.update { it.copy(status = DictationStatus.FINISHING) }
        synchronized(audioLock) {
            if (socketOpen) {
                flushPendingAudioLocked(activeSocket)
                activeSocket.send("""{"type":"Finalize"}""")
            } else {
                // Keep Finalize behind the opening audio when the user stops during the handshake.
                finalizeWhenOpen = true
            }
        }
        finishingJob?.cancel()
        finishingJob = scope.launch {
            delay(FINAL_RESULT_WAIT_MS)
            synchronized(audioLock) {
                if (socketOpen) {
                    activeSocket.send("""{"type":"CloseStream"}""")
                    activeSocket.close(1000, "Dictation complete")
                } else {
                    closeWhenOpen = true
                }
            }
        }
    }

    /** Drops an in-progress recording and its transcript when its owning chat closes. */
    @Synchronized
    fun discard() {
        val configured = _state.value.configured
        finishingJob?.cancel()
        finishingJob = null
        stopping = false
        stopRecorder()
        socket?.cancel()
        socket = null
        synchronized(audioLock) {
            pendingAudio.reset()
            socketOpen = false
            finalizeWhenOpen = false
            closeWhenOpen = false
        }
        synchronized(finalSegments) { finalSegments.clear() }
        _state.value = DictationState(configured = configured)
    }

    private fun startRecorder() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                failAndCancelSocket("Microphone permission was removed")
                return@launch
            }
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) {
                failAndCancelSocket("The microphone could not be initialized")
                return@launch
            }
            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, AUDIO_BUFFER_BYTES),
                )
            } catch (_: SecurityException) {
                failAndCancelSocket("Microphone permission was removed")
                return@launch
            }
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                failAndCancelSocket("The microphone could not be initialized")
                return@launch
            }
            recorder = audioRecord
            audioRecord.startRecording()
            _state.update { it.copy(status = DictationStatus.LISTENING, error = null) }
            val buffer = ByteArray(maxOf(minimum, AUDIO_BUFFER_BYTES))
            try {
                while (isActive && recorder === audioRecord) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count > 0 && !sendOrBufferAudio(buffer, count)) break
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
        synchronized(audioLock) {
            pendingAudio.reset()
            socketOpen = false
            finalizeWhenOpen = false
            closeWhenOpen = false
        }
        _state.update { it.copy(status = DictationStatus.FAILED, error = message) }
    }

    private fun failAndCancelSocket(message: String) {
        val activeSocket = socket
        fail(message)
        activeSocket?.cancel()
    }

    private fun sendOrBufferAudio(buffer: ByteArray, count: Int): Boolean {
        val activeSocket = socket
        synchronized(audioLock) {
            if (activeSocket != null && socketOpen) {
                return activeSocket.send(buffer.toByteString(0, count))
            }
            // Keep the beginning of the utterance. A normal handshake is far below this limit,
            // but refusing newer samples is safer than evicting the words already spoken.
            if (pendingAudio.size() + count <= MAX_PENDING_AUDIO_BYTES) {
                pendingAudio.write(buffer, 0, count)
            }
            return true
        }
    }

    private fun flushPendingAudioLocked(activeSocket: WebSocket) {
        if (pendingAudio.size() == 0) return
        activeSocket.send(pendingAudio.toByteArray().toByteString())
        pendingAudio.reset()
    }

    override fun close() {
        finishingJob?.cancel()
        stopRecorder()
        socket?.cancel()
        socket = null
        synchronized(audioLock) {
            pendingAudio.reset()
            socketOpen = false
            finalizeWhenOpen = false
            closeWhenOpen = false
        }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) {
                webSocket.cancel()
                return
            }
            synchronized(audioLock) {
                socketOpen = true
                flushPendingAudioLocked(webSocket)
                if (finalizeWhenOpen) {
                    finalizeWhenOpen = false
                    webSocket.send("""{"type":"Finalize"}""")
                }
                if (closeWhenOpen) {
                    closeWhenOpen = false
                    webSocket.send("""{"type":"CloseStream"}""")
                    webSocket.close(1000, "Dictation complete")
                }
            }
            _state.update { it.copy(status = DictationStatus.LISTENING, error = null) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            DeepgramTranscriptParser.parse(text)?.let(::acceptTranscript)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            finishWithoutError()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            // A deliberate stop tears the socket down and can still surface here as a
            // failure; stay quiet about anything arriving after stop() was asked for.
            if (stopping) {
                finishWithoutError()
                return
            }
            val message = when (response?.code) {
                401, 403 -> "Deepgram rejected the API key"
                else -> "Dictation connection failed"
            }
            fail(message)
        }
    }

    /**
     * Shared teardown for a socket that ended without an error: recorder stopped,
     * buffers reset, flags cleared, back to idle.
     */
    private fun finishWithoutError() {
        stopRecorder()
        socket = null
        synchronized(audioLock) {
            pendingAudio.reset()
            socketOpen = false
            finalizeWhenOpen = false
            closeWhenOpen = false
        }
        stopping = false
        _state.update { it.copy(status = DictationStatus.IDLE) }
    }

    private companion object {
        const val API_KEY_SECRET_ID = "deepgram_api_key"
        const val MAX_API_KEY_CHARACTERS = 512
        const val SAMPLE_RATE = 16_000
        const val AUDIO_BUFFER_BYTES = 3_200
        const val MAX_PENDING_AUDIO_BYTES = SAMPLE_RATE * 2 * 8
        const val FINAL_RESULT_WAIT_MS = 1_200L
        const val DEEPGRAM_LISTEN_URL =
            "wss://api.deepgram.com/v1/listen" +
                "?model=nova-3&language=en&smart_format=true&punctuate=true" +
                "&interim_results=true&endpointing=300&encoding=linear16" +
                "&sample_rate=$SAMPLE_RATE&channels=1"
        val ACTIVE_STATUSES = setOf(
            DictationStatus.CONNECTING,
            DictationStatus.LISTENING,
            DictationStatus.FINISHING,
        )
    }
}
