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

    fun afterFailure(error: Throwable, linkAlive: Boolean, attempt: Int, reused: Boolean): Outcome {
        val lost = error is Stalled || !linkAlive
        val unsent = error is SshProblem.LinkUnavailable
        return Outcome(evict = lost, retry = attempt == 0 && reused && (unsent || lost))
    }

    fun expired(inUse: Int, lastUsedAtMillis: Long, now: Long, idleMs: Long = IDLE_MS): Boolean =
        inUse == 0 && now - lastUsedAtMillis >= idleMs
}

class LinkPool<L : Any>(
    private val isAlive: (L) -> Boolean,
    private val close: suspend (L) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal class Held<L>(val identity: LinkPolicy.Identity, val link: L, var inUse: Int, var lastUsedAtMillis: Long)

    class Rented<L : Any> internal constructor(
        private val pool: LinkPool<L>,
        internal val hostId: String,
        internal val identity: LinkPolicy.Identity,
        internal val open: suspend () -> L,
        internal var entry: Held<L>,
        internal var reused: Boolean,
    ) {
        private val lifecycle = Mutex()
        private var released = false
        val link: L get() = entry.link

        suspend fun <T> use(block: suspend (L) -> T): T = lifecycle.withLock {
            check(!released)
            pool.run(this, stallMs = null, retryLost = false, block)
        }

        suspend fun release() = withContext(NonCancellable) {
            lifecycle.withLock {
                if (!released) {
                    released = true
                    pool.release(hostId, entry)
                }
            }
        }
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val held = ConcurrentHashMap<String, MutableList<Held<L>>>()

    suspend fun <T> use(
        hostId: String,
        identity: LinkPolicy.Identity,
        stallMs: Long?,
        open: suspend () -> L,
        block: suspend (L) -> T,
    ): T {
        val rented = rent(hostId, identity, open)
        try {
            return run(rented, stallMs, retryLost = stallMs != null, block)
        } finally {
            rented.release()
        }
    }

    suspend fun rent(
        hostId: String,
        identity: LinkPolicy.Identity,
        open: suspend () -> L,
    ): Rented<L> {
        val (entry, reused) = acquire(hostId, identity, open)
        return Rented(this, hostId, identity, open, entry, reused)
    }

    private suspend fun <T> run(
        rented: Rented<L>,
        stallMs: Long?,
        retryLost: Boolean,
        block: suspend (L) -> T,
    ): T {
        var attempt = 0
        var openedOverflow = false
        val refused = mutableSetOf<L>()
        if (!isAlive(rented.link)) {
            val previous = rented.entry
            val (next, reused) = acquire(rented.hostId, rented.identity, rented.open)
            rented.entry = next
            rented.reused = reused
            release(rented.hostId, previous)
        }
        while (true) {
            val entry = rented.entry
            try {
                return if (rented.reused && stallMs != null) onReused(entry.link, stallMs, block) else block(entry.link)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { if (!isAlive(entry.link)) evict(rented.hostId, entry) }
                throw cancelled
            } catch (error: Throwable) {
                val alive = isAlive(entry.link)
                val channelRefused = error is SshProblem.LinkUnavailable && alive
                if (channelRefused) {
                    if (openedOverflow) throw error
                    refused.add(entry.link)
                } else {
                    val outcome = LinkPolicy.afterFailure(error, alive, attempt, rented.reused)
                    if (outcome.evict) evict(rented.hostId, entry)
                    if (!outcome.retry || (!retryLost && error !is SshProblem.LinkUnavailable)) throw error
                    attempt++
                }
                val (next, reused) = acquire(rented.hostId, rented.identity, rented.open, refused)
                rented.entry = next
                rented.reused = reused
                if (channelRefused && !reused) openedOverflow = true
                release(rented.hostId, entry)
            }
        }
    }

    private suspend fun release(hostId: String, entry: Held<L>) = withContext(NonCancellable) {
        lock(hostId).withLock {
            entry.inUse--
            entry.lastUsedAtMillis = now()
        }
    }

    suspend fun sweep(idleMs: Long = LinkPolicy.IDLE_MS) {
        held.keys.toList().forEach { hostId ->
            val expired = lock(hostId).withLock {
                val entries = held[hostId] ?: return@withLock emptyList()
                entries.filter { LinkPolicy.expired(it.inUse, it.lastUsedAtMillis, now(), idleMs) }
                    .also { entries.removeAll(it.toSet()) }
            }
            expired.forEach { runCatching { close(it.link) } }
        }
    }

    suspend fun dropAll() {
        held.keys.toList().forEach { hostId ->
            val dropped = lock(hostId).withLock { held.remove(hostId)?.toList().orEmpty() }
            dropped.forEach { runCatching { close(it.link) } }
        }
    }

    private fun lock(hostId: String): Mutex = locks.computeIfAbsent(hostId) { Mutex() }

    private suspend fun acquire(
        hostId: String,
        identity: LinkPolicy.Identity,
        open: suspend () -> L,
        refused: Set<L> = emptySet(),
    ): Pair<Held<L>, Boolean> = lock(hostId).withLock {
        val entries = held.getOrPut(hostId) { mutableListOf() }
        val invalid = entries.filter { LinkPolicy.acquire(it.identity, identity, isAlive(it.link)) == LinkPolicy.Acquire.OPEN }
        entries.removeAll(invalid.toSet())
        invalid.forEach { runCatching { close(it.link) } }
        val current = entries.firstOrNull { it.link !in refused }
        val chosen = if (current != null) {
            current to true
        } else {
            Held(identity, open(), inUse = 0, lastUsedAtMillis = now()).also(entries::add) to false
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
        lock(hostId).withLock { held[hostId]?.remove(entry) }
        runCatching { close(entry.link) }
    }
}
