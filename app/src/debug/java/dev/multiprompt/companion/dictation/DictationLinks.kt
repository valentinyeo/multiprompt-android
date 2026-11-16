package dev.multiprompt.companion.dictation

import dev.multiprompt.companion.BuildConfig

internal object DictationLinks {
    fun listenUrl(index: Int): String = standIn() ?: DeepgramEndpoint.url(index)

    fun keyCheckUrl(): String {
        val override = standIn() ?: return liveKeyCheckUrl()
        val http = override.replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
        return http.substringBefore("?") + "?model=nova-3"
    }

    private fun standIn(): String? {
        val value = BuildConfig.DICTATION_LISTEN_URL.trim()
        if (value.startsWith("ws://10.0.2.2:") || value.startsWith("wss://10.0.2.2:")) return value
        return null
    }

    private fun liveKeyCheckUrl(): String = "https://${DeepgramEndpoint.HOSTS.last()}/v1/listen?model=nova-3"
}
