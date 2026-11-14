package dev.multiprompt.companion.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal fun streamWanted(selected: Flow<Boolean>, confirmationPending: Flow<Boolean>): Flow<Boolean> =
    combine(selected, confirmationPending) { onScreen, pending -> onScreen || pending }

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
) {
    suspend fun pump() {
        var warm: C? = null
        wanted.distinctUntilChanged().collectLatest { visible ->
            if (ChatPollGate.step(visible) == ChatPollGate.Step.WAIT_FOR_WAKE) return@collectLatest
            while (true) {
                var reused = warm != null
                try {
                    val client = warm ?: run {
                        onConnecting()
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
                if (!reused) delay(reconnectDelayMillis)
            }
        }
    }
}

private class StaleWarmClient : RuntimeException("The warm connection sent nothing after it was reopened")
