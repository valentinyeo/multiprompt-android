package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.HostProfile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object LinkPolicy {
    const val IDLE_MS = 60_000L
    const val KEEPALIVE_MS = 15_000L
    const val LIST_STALL_MS = 12_000L

    data class Identity(
        val hostname: String,
        val port: Int,
        val username: String,
        val keySecretId: String,
        val passphraseSecretId: String?,
        val hostKeyType: String?,
        val hostKeyFingerprint: String?,
        val hostKeys: List<String>,
    )

    enum class Acquire { REUSE, OPEN }

    data class Outcome(val evict: Boolean, val retry: Boolean)

    class Stalled : Exception("The server link stopped answering. Refresh to retry.")

    fun identity(host: HostProfile) = Identity(
        hostname = host.hostname,
        port = host.port,
        username = host.username,
        keySecretId = host.keySecretId,
        passphraseSecretId = host.passphraseSecretId,
        hostKeyType = host.hostKeyType,
        hostKeyFingerprint = host.hostKeyFingerprint,
        hostKeys = host.hostKeys,
    )

    fun acquire(held: Identity?, wanted: Identity, heldAlive: Boolean): Acquire =
        if (held == wanted && heldAlive) Acquire.REUSE else Acquire.OPEN

    fun afterFailure(error: Throwable, linkAlive: Boolean, attempt: Int, reused: Boolean, repeatable: Boolean): Outcome {
        val lost = error is Stalled || !linkAlive
        val unsent = error is SshProblem.LinkUnavailable
        return Outcome(evict = lost, retry = attempt == 0 && reused && (unsent || (lost && repeatable)))
    }

    fun expired(inUse: Int, lastUsedAtMillis: Long, now: Long, idleMs: Long = IDLE_MS): Boolean =
        inUse == 0 && now - lastUsedAtMillis >= idleMs
}

class LinkPool<L : Any>(
    private val isAlive: (L) -> Boolean,
    private val close: suspend (L) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Held<L>(val identity: LinkPolicy.Identity, val link: L, var inUse: Int, var lastUsedAtMillis: Long)

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val held = ConcurrentHashMap<String, Held<L>>()

    suspend fun <T> use(
        hostId: String,
        identity: LinkPolicy.Identity,
        repeatable: Boolean,
        stallMs: Long?,
        open: suspend () -> L,
        block: suspend (L) -> T,
    ): T {
        var attempt = 0
        while (true) {
            val (entry, reused) = acquire(hostId, identity, open)
            try {
                return if (reused && stallMs != null) onReused(entry.link, stallMs, block) else block(entry.link)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { evict(hostId, entry) }
                throw cancelled
            } catch (error: Throwable) {
                val outcome = LinkPolicy.afterFailure(error, isAlive(entry.link), attempt, reused, repeatable)
                if (outcome.evict) evict(hostId, entry)
                if (!outcome.retry) throw error
                attempt++
            } finally {
                withContext(NonCancellable) {
                    lock(hostId).withLock {
                        entry.inUse--
                        entry.lastUsedAtMillis = now()
                    }
                }
            }
        }
    }

    suspend fun sweep(idleMs: Long = LinkPolicy.IDLE_MS) {
        held.keys.toList().forEach { hostId ->
            val expired = lock(hostId).withLock {
                held[hostId]?.takeIf { LinkPolicy.expired(it.inUse, it.lastUsedAtMillis, now(), idleMs) }
                    ?.also { held.remove(hostId, it) }
            }
            expired?.let { runCatching { close(it.link) } }
        }
    }

    suspend fun dropAll() {
        held.keys.toList().forEach { hostId ->
            val dropped = lock(hostId).withLock { held.remove(hostId) }
            dropped?.let { runCatching { close(it.link) } }
        }
    }

    private fun lock(hostId: String): Mutex = locks.computeIfAbsent(hostId) { Mutex() }

    private suspend fun acquire(
        hostId: String,
        identity: LinkPolicy.Identity,
        open: suspend () -> L,
    ): Pair<Held<L>, Boolean> = lock(hostId).withLock {
        val current = held[hostId]
        val decision = LinkPolicy.acquire(current?.identity, identity, current?.let { isAlive(it.link) } ?: false)
        val chosen = if (decision == LinkPolicy.Acquire.REUSE && current != null) {
            current to true
        } else {
            current?.let {
                held.remove(hostId, it)
                runCatching { close(it.link) }
            }
            Held(identity, open(), inUse = 0, lastUsedAtMillis = now()).also { held[hostId] = it } to false
        }
        chosen.first.inUse++
        chosen.first.lastUsedAtMillis = now()
        chosen
    }

    private suspend fun <T> onReused(link: L, stallMs: Long, block: suspend (L) -> T): T = try {
        withTimeout(stallMs) { block(link) }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw LinkPolicy.Stalled()
    }

    private suspend fun evict(hostId: String, entry: Held<L>) {
        held.remove(hostId, entry)
        runCatching { close(entry.link) }
    }
}
