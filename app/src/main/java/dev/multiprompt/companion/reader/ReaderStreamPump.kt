package dev.multiprompt.companion.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class ModelSwitchConfirmation(
    private val now: () -> Long,
    private val timeoutMillis: Long,
) {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    @Volatile
    private var deadline = 0L

    fun begin() {
        deadline = now() + timeoutMillis
        _pending.value = true
    }

    fun expire(): Boolean {
        if (!_pending.value || now() <= deadline) return false
        _pending.value = false
        return true
    }

    fun shouldPress(visible: Boolean): Boolean {
        if (!_pending.value || !visible) return false
        val due = now() <= deadline
        _pending.value = false
        return due
    }
}

internal suspend fun paneFrames(pane: StateFlow<Boolean>, frames: suspend () -> Unit) {
    pane.collectLatest { visible -> if (visible) frames() }
}

internal class ReaderStreamPump<C : Any>(
    private val wanted: StateFlow<Boolean>,
    private val connect: suspend () -> C,
    private val stream: suspend (C, () -> Unit) -> Unit,
    private val release: suspend (C) -> Unit,
    private val onClient: (C?) -> Unit,
    private val onConnecting: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val reconnectDelayMillis: Long,
    private val reuseFirstBytesMillis: Long,
    private val reconnectWait: suspend (Long) -> Unit = { delay(it) },
) {
    var streamStarts = 0
        private set

    var connects = 0
        private set

    suspend fun pump() {
        var warm: C? = null
        wanted.collectLatest {
            if (!wanted.value) return@collectLatest
            while (wanted.value) {
                val wasWarm = warm != null
                var answered = false
                try {
                    val client = warm ?: run {
                        onConnecting()
                        connects += 1
                        connect().also {
                            warm = it
                            onClient(it)
                        }
                    }
                    coroutineScope {
                        val watchdog = if (wasWarm) {
                            launch {
                                delay(reuseFirstBytesMillis)
                                throw StaleWarmClient()
                            }
                        } else {
                            null
                        }
                        streamStarts += 1
                        stream(client) {
                            answered = true
                            watchdog?.cancel()
                        }
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) currentCoroutineContext().ensureActive()
                    if (answered || !wasWarm) onFailure(throwable)
                }
                if (!answered) {
                    val dropped = warm
                    warm = null
                    onClient(null)
                    dropped?.let { release(it) }
                }
                if (answered || !wasWarm) reconnectWait(reconnectDelayMillis)
            }
        }
    }
}

private class StaleWarmClient : RuntimeException("The warm connection sent nothing after it was reopened")
