package dev.multiprompt.companion.computer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the Computer view shows. Mirrors the approved states frame. */
enum class ComputerStatus { Sleeping, Connecting, Live, Reconnecting, Lost }

data class ComputerState(
    val status: ComputerStatus = ComputerStatus.Sleeping,
    val localPort: Int? = null,
    val failure: String? = null,
)

/** The local end of one tunnel, reached through the session's SSH connection. */
interface ComputerTunnel : AutoCloseable {
    val localPort: Int
}

fun interface ComputerTunnelOpener {
    suspend fun open(target: ComputerTarget): ComputerTunnel?
}

/**
 * Owns the tunnel behind the Computer view. Only a visible pane holds one, so a
 * hidden chat costs nothing: sleep closes the tunnel and keeps the last picture.
 */
class ComputerViewer(
    private val opener: ComputerTunnelOpener,
    private val publish: (ComputerState) -> Unit,
    private val retries: Int = 1,
) {
    private val mutex = Mutex()
    private var tunnel: ComputerTunnel? = null
    private var everConnected = false

    var current: ComputerState = ComputerState()
        private set

    suspend fun wake(target: ComputerTarget) = mutex.withLock {
        if (current.status == ComputerStatus.Live) return@withLock
        var attempt = 0
        while (true) {
            emit(
                ComputerState(
                    if (everConnected) ComputerStatus.Reconnecting else ComputerStatus.Connecting,
                    localPort = if (everConnected) current.localPort else null,
                ),
            )
            val opened = runCatching { opener.open(target) }.getOrNull()
            if (opened != null) {
                tunnel = opened
                everConnected = true
                emit(ComputerState(ComputerStatus.Live, localPort = opened.localPort))
                return@withLock
            }
            if (attempt >= retries) {
                emit(ComputerState(ComputerStatus.Lost, failure = "Cannot reach the VPS"))
                return@withLock
            }
            attempt++
        }
    }

    fun sleep() {
        val old = tunnel
        tunnel = null
        runCatching { old?.close() }
        emit(ComputerState(ComputerStatus.Sleeping))
    }

    fun close() = sleep()

    private fun emit(next: ComputerState) {
        current = next
        publish(next)
    }
}
