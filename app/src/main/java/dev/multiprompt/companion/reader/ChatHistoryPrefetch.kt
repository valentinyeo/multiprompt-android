package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.model.HostProfile
import kotlinx.coroutines.CancellationException

object ChatHistoryPrefetch {
    private val managed = setOf(AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.PI)

    fun warm(cache: ChatHistoryCache, host: HostProfile, identities: List<ChatHistoryProtocol.Identity>) {
        identities.forEach { identity ->
            val key = ChatHistoryConnection.cacheKey(host, identity)
            if (cache.peek(key) == null) runCatching { cache.restore(key) }
        }
    }

    suspend fun refresh(
        cache: ChatHistoryCache,
        host: HostProfile,
        identities: List<ChatHistoryProtocol.Identity>,
        exchange: suspend (String) -> String,
    ) {
        var id = 0L
        for (identity in identities) {
            if (!TranscriptProtocol.supports(identity.agent)) continue
            val key = ChatHistoryConnection.cacheKey(host, identity)
            val cached = cache.peek(key) ?: runCatching { cache.restore(key) }.getOrNull() ?: ChatHistory()
            val next = (try {
                fetch(++id, identity, cached, exchange)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                fetch(++id, identity, ChatHistory(), exchange)
            }) ?: continue
            if (next.sid.isNotEmpty() && next != cached) runCatching { cache.save(key, next) }
        }
    }

    private suspend fun fetch(id: Long, identity: ChatHistoryProtocol.Identity, base: ChatHistory, exchange: suspend (String) -> String): ChatHistory? =
        ChatHistoryProtocol.parse(exchange(ChatHistoryProtocol.request(id, identity, base, held = identity.agent !in managed)), id, identity)?.let { base.apply(it) }
}
