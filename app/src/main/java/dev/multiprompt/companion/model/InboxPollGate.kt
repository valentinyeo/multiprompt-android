package dev.multiprompt.companion.model

internal class InboxPollGate {
    private val applied = mutableMapOf<String, String>()

    fun wantsListing(hostId: String, signature: String?): Boolean {
        if (signature == null) return true
        if (applied[hostId] == signature) return false
        applied[hostId] = signature
        return true
    }

    fun forget(hostId: String) {
        applied.remove(hostId)
    }

    fun forgetAll() = applied.clear()
}
