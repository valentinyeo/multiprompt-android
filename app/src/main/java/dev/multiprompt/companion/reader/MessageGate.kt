package dev.multiprompt.companion.reader

import kotlinx.coroutines.sync.Mutex

class MessageGate {
    private data class Owner(val token: Any, val mutex: Mutex)
    private val owners = mutableMapOf<String, Owner>()

    @Synchronized fun claim(key: String, token: Any): Mutex {
        val mutex = owners[key]?.mutex ?: Mutex()
        owners[key] = Owner(token, mutex)
        return mutex
    }

    @Synchronized fun owns(key: String, token: Any): Boolean = owners[key]?.token === token

    @Synchronized fun claimIfOwned(key: String, token: Any): Mutex? = owners[key]?.takeIf { it.token === token }?.mutex

    @Synchronized fun <T> ifOwned(key: String, token: Any, block: () -> T): T? =
        if (owners[key]?.token === token) block() else null

    @Synchronized fun release(key: String, token: Any) {
        val owner = owners[key]
        if (owner?.token === token) owners[key] = Owner(Any(), owner.mutex)
    }
}
