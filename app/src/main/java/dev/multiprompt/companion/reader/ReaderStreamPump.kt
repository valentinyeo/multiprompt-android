package dev.multiprompt.companion.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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

internal fun Flow<Boolean>.settledVisibility(millis: Long): Flow<Boolean> = channelFlow {
    var shown = false
    var stop: Job? = null
    collect { value ->
        if (value) {
            stop?.cancel()
            stop = null
            if (!shown) {
                shown = true
                send(true)
            }
        } else if (shown) {
            stop?.cancel()
            stop = launch {
                delay(millis)
                shown = false
                send(false)
            }
        }
    }
}

internal class ReaderStreamPump<C : Any>(
    private val wanted: Flow<Boolean>,
    private val connect: suspend () -> C,
    private val stream: suspend (C, () -> Unit) -> Unit,
    private val release: suspend (C) -> Unit,
    private val onClient: (C?) -> Unit,
    private val onConnecting: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val reconnectDelayMillis: Long,
    private val reuseFirstBytesMillis: Long,
    private val reconnectWait: suspend (Long) -> Unit = { delay(it) },
    private val hiddenSettleMillis: Long = HIDDEN_SETTLE_MS,
) {
    var streamStarts = 0
        private set

    var connects = 0
        private set

    suspend fun pump() {
        var warm: C? = null
        wanted.distinctUntilChanged().settledVisibility(hiddenSettleMillis).collectLatest { visible ->
            if (!visible) return@collectLatest
            while (true) {
                var reused = warm != null
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
                        val watchdog = if (reused) {
                            launch {
                                delay(reuseFirstBytesMillis)
                                throw StaleWarmClient()
                            }
                        } else {
                            null
                        }
                        streamStarts += 1
                        stream(client) {
                            reused = false
                            watchdog?.cancel()
                        }
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) currentCoroutineContext().ensureActive()
                    if (!reused) onFailure(throwable)
                }
                val dropped = warm
                warm = null
                onClient(null)
                dropped?.let { release(it) }
                if (!reused) reconnectWait(reconnectDelayMillis)
            }
        }
    }

    companion object {
        const val HIDDEN_SETTLE_MS = 900L
    }
}

private class StaleWarmClient : RuntimeException("The warm connection sent nothing after it was reopened")
