package dev.multiprompt.companion.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal class ReaderStreamPump<C : Any>(
    private val selected: Flow<Boolean>,
    private val connect: suspend () -> C,
    private val stream: suspend (C, () -> Unit) -> Unit,
    private val release: suspend (C) -> Unit,
    private val onClient: (C?) -> Unit,
    private val onConnecting: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val reconnectDelayMillis: Long,
    private val reuseFirstFrameMillis: Long,
) {
    suspend fun run() {
        var warm: C? = null
        selected.distinctUntilChanged().collectLatest { visible ->
            if (ChatPollGate.step(visible) == ChatPollGate.Step.WAIT_FOR_WAKE) return@collectLatest
            while (true) {
                val reused = warm != null
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
                                delay(reuseFirstFrameMillis)
                                throw StaleWarmClient()
                            }
                        } else {
                            null
                        }
                        stream(client) { watchdog?.cancel() }
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) currentCoroutineContext().ensureActive()
                    if (!reused) onFailure(throwable)
                }
                warm?.let { release(it) }
                warm = null
                onClient(null)
                if (!reused) delay(reconnectDelayMillis)
            }
        }
    }
}

private class StaleWarmClient : RuntimeException("The warm connection sent nothing after it was reopened")
