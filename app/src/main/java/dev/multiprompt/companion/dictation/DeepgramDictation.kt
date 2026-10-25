package dev.multiprompt.companion.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.multiprompt.companion.security.SecretStorage
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
    val sessionKey: String? = null,
)

class DeepgramDictation(
    context: Context,
    private val secrets: SecretStorage,
    private val micFactory: () -> MicSource? = { AudioRecordMicSource.create() },
    private val socketFactory: ((Request, WebSocketListener) -> WebSocket)? = null,
    private val usageLog: DictationUsageLog = DictationUsageLog(PreferencesDictationUsageStore(context)),
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
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private class Stream {
        val pendingAudio = ByteArrayOutputStream()
        var open = false
        var finalizeWhenOpen = false
        var closeWhenOpen = false
        var gateClosed = false
    }

    private val liveSockets = mutableMapOf<WebSocket, Stream>()
    private var socket: WebSocket? = null
    private var recorder: MicSource? = null
    private var recordingJob: Job? = null
    private var finishingJob: Job? = null
    private var durationJob: Job? = null
    private var stopping = false
    private var gate: SpeechGate? = null
    private var usageRecorded = true

    fun saveApiKey(value: String): Boolean {
        val key = value.trim()
        if (key.isBlank() || key.length > MAX_API_KEY_CHARACTERS) return false
        secrets.put(API_KEY_SECRET_ID, key.toByteArray(Charsets.UTF_8))
        _state.update { it.copy(configured = true, error = null) }
        return true
    }

    @Synchronized
    fun removeApiKey() {
        discard()
        secrets.remove(API_KEY_SECRET_ID)
        _state.value = DictationState(configured = false)
    }

    @Synchronized
    fun start(sessionKey: String) {
        if (_state.value.status in ACTIVE_STATUSES) return
        _state.value = DictationState(configured = _state.value.configured, sessionKey = sessionKey)
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
                _state.value = DictationState(configured = false, error = "Add a Deepgram API key", sessionKey = sessionKey)
                return
            }

        finishingJob?.cancel()
        cancelSockets()
        finalSegments.clear()
        stopping = false
        gate = SpeechGate()
        usageRecorded = false
        _state.value = DictationState(status = DictationStatus.LISTENING, configured = true, sessionKey = sessionKey)
        val mic = try {
            micFactory()
        } catch (_: SecurityException) {
            fail("Microphone permission was removed")
            return
        } catch (_: IllegalArgumentException) {
            null
        }
        recorder = mic
        if (mic == null || !runCatching { mic.start() }.getOrDefault(false)) {
            fail("The microphone could not be initialized")
            return
        }
        startRecorder(mic, apiKey)
        durationJob = scope.launch {
            delay(SpeechGate.MAX_DURATION_MS.toLong())
            stop()
        }
    }

    @Synchronized
    fun stop() {
        if (_state.value.status !in ACTIVE_STATUSES || stopping) return
        stopping = true
        stopRecorder()
        recordUsage()
        if (liveSockets.isEmpty()) {
            finishWithoutError()
            return
        }
        _state.update { it.copy(status = DictationStatus.FINISHING) }
        socket?.let { active ->
            liveSockets[active]?.let { stream -> finalizeStream(active, stream) }
        }
        finishingJob?.cancel()
        finishingJob = scope.launch {
            delay(FINAL_RESULT_WAIT_MS)
            synchronized(this@DeepgramDictation) {
                liveSockets.forEach { (active, stream) -> closeStream(active, stream) }
            }
            delay(FINAL_RESULT_WAIT_MS)
            synchronized(this@DeepgramDictation) {
                if (stopping) {
                    cancelSockets()
                    finishWithoutError()
                }
            }
        }
    }

    fun onAppBackgrounded() = stop()

    @Synchronized
    fun discard() {
        finishingJob?.cancel()
        finishingJob = null
        stopRecorder()
        recordUsage()
        cancelSockets()
        stopping = false
        finalSegments.clear()
        _state.value = DictationState(configured = _state.value.configured)
    }

    private fun startRecorder(mic: MicSource, apiKey: String) {
        recordingJob = scope.launch {
            val buffer = ByteArray(AUDIO_BUFFER_BYTES)
            try {
                while (isActive) {
                    val count = mic.read(buffer)
                    if (count == 0) {
                        delay(10)
                        continue
                    }
                    synchronized(this@DeepgramDictation) {
                        if (recorder !== mic || stopping) return@launch
                        if (count < 0) {
                            fail("The microphone could not be read")
                            return@launch
                        }
                        _level.value = AudioLevel.of(buffer, count)
                        gate?.process(buffer, count)?.forEach { action ->
                            if (recorder === mic && !stopping) handleAction(action, apiKey)
                        }
                    }
                }
            } catch (_: Exception) {
                synchronized(this@DeepgramDictation) {
                    if (recorder === mic && !stopping) fail("The microphone could not be read")
                }
            }
        }
    }

    private fun handleAction(action: GateAction, apiKey: String) {
        when (action) {
            GateAction.OpenStream -> openSocket(apiKey, 0, Stream())
            is GateAction.Send -> if (!sendOrBufferAudio(action.bytes)) fail("Dictation audio could not be sent")
            GateAction.KeepAlive -> socket?.let { active ->
                if (liveSockets[active]?.open == true) active.send("""{"type":"KeepAlive"}""")
            }
            GateAction.Finalize -> socket?.let { active ->
                liveSockets[active]?.let { finalizeStream(active, it) }
            }
            GateAction.CloseStream -> socket?.let { active ->
                liveSockets[active]?.let {
                    it.gateClosed = true
                    closeStream(active, it)
                }
                socket = null
            }
            is GateAction.StopDictation -> stop()
        }
    }

    private fun acceptTranscript(result: DeepgramTranscript) {
        if (result.isFinal) finalSegments += result.text
        val finalText = finalSegments.joinToString(" ")
        val combined = if (result.isFinal) {
            finalText
        } else {
            listOf(finalText, result.text).filter(String::isNotBlank).joinToString(" ")
        }
        _state.update { it.copy(transcript = combined) }
    }

    private fun stopRecorder() {
        _level.value = 0f
        val activeRecorder = recorder
        recorder = null
        runCatching { activeRecorder?.stop() }
        runCatching { activeRecorder?.release() }
        recordingJob?.cancel()
        recordingJob = null
        durationJob?.cancel()
        durationJob = null
    }

    private fun recordUsage() {
        if (usageRecorded) return
        usageRecorded = true
        val seconds = gate?.sentSeconds ?: 0.0
        val total = usageLog.add(seconds)
        Log.i("Dictation", "Streamed ${seconds}s this dictation; ${total}s total today")
    }

    private fun fail(message: String) {
        stopRecorder()
        recordUsage()
        cancelSockets()
        stopping = false
        _state.update { it.copy(status = DictationStatus.FAILED, error = message) }
    }

    private fun sendOrBufferAudio(buffer: ByteArray): Boolean {
        val active = socket ?: return false
        val stream = liveSockets[active] ?: return false
        if (stream.open) return active.send(buffer.toByteString())
        if (stream.pendingAudio.size() + buffer.size > MAX_PENDING_AUDIO_BYTES) return false
        stream.pendingAudio.write(buffer)
        return true
    }

    private fun finalizeStream(active: WebSocket, stream: Stream) {
        if (stream.open) active.send("""{"type":"Finalize"}""") else stream.finalizeWhenOpen = true
    }

    private fun closeStream(active: WebSocket, stream: Stream) {
        if (stream.open) {
            active.send("""{"type":"CloseStream"}""")
            active.close(1000, "Dictation complete")
        } else {
            stream.closeWhenOpen = true
        }
    }

    private fun cancelSockets() {
        val sockets = liveSockets.keys.toList()
        liveSockets.clear()
        socket = null
        sockets.forEach { it.cancel() }
    }

    @Synchronized
    override fun close() {
        discard()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }

    private fun openSocket(apiKey: String, hostIndex: Int, stream: Stream) {
        val request = Request.Builder()
            .url(DeepgramEndpoint.url(hostIndex))
            .header("Authorization", "Token $apiKey")
            .build()
        val listener = Listener(apiKey, hostIndex)
        val active = socketFactory?.invoke(request, listener) ?: client.newWebSocket(request, listener)
        liveSockets[active] = stream
        socket = active
    }

    private inner class Listener(
        private val apiKey: String,
        private val hostIndex: Int,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@DeepgramDictation) {
            val stream = liveSockets[webSocket] ?: run {
                webSocket.cancel()
                return@synchronized
            }
            stream.open = true
            if (stream.pendingAudio.size() > 0) {
                val sent = webSocket.send(stream.pendingAudio.toByteArray().toByteString())
                stream.pendingAudio.reset()
                if (!sent && !stream.gateClosed) {
                    fail("Dictation audio could not be sent")
                    return@synchronized
                }
            }
            if (stream.finalizeWhenOpen) {
                stream.finalizeWhenOpen = false
                finalizeStream(webSocket, stream)
            }
            if (stream.closeWhenOpen) {
                stream.closeWhenOpen = false
                closeStream(webSocket, stream)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@DeepgramDictation) {
            if (webSocket in liveSockets) DeepgramTranscriptParser.parse(text)?.let(::acceptTranscript)
            Unit
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@DeepgramDictation) {
            val stream = liveSockets.remove(webSocket) ?: return@synchronized
            if (socket === webSocket) socket = null
            if (stopping) {
                if (liveSockets.isEmpty()) finishWithoutError()
            } else if (!stream.gateClosed) {
                finishWithoutError()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@DeepgramDictation) {
            val stream = liveSockets.remove(webSocket) ?: return@synchronized
            if (socket === webSocket) socket = null
            if (stopping) {
                if (liveSockets.isEmpty()) finishWithoutError()
            } else if (!stream.gateClosed) {
                if (DeepgramEndpoint.shouldTryNextHost(hostIndex, response?.code, stream.open, stopping)) {
                    openSocket(apiKey, hostIndex + 1, stream)
                } else {
                    fail(DeepgramEndpoint.failureMessage(response?.code, t))
                }
            }
        }
    }

    private fun finishWithoutError() {
        stopRecorder()
        recordUsage()
        cancelSockets()
        stopping = false
        _state.update { it.copy(status = DictationStatus.IDLE) }
    }

    private companion object {
        const val API_KEY_SECRET_ID = "deepgram_api_key"
        const val MAX_API_KEY_CHARACTERS = 512
        const val AUDIO_BUFFER_BYTES = 3_200
        const val MAX_PENDING_AUDIO_BYTES = SpeechGate.SAMPLE_RATE * 2 * 8
        const val FINAL_RESULT_WAIT_MS = 1_200L
        val ACTIVE_STATUSES = setOf(
            DictationStatus.CONNECTING,
            DictationStatus.LISTENING,
            DictationStatus.FINISHING,
        )
    }
}
