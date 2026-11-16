package dev.multiprompt.companion.dictation

internal object DictationLinks {
    fun listenUrl(index: Int): String = DeepgramEndpoint.url(index)

    fun keyCheckUrl(): String = "https://${DeepgramEndpoint.HOSTS.last()}/v1/listen?model=nova-3"
}
