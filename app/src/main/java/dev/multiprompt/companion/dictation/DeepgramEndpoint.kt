package dev.multiprompt.companion.dictation

object DeepgramEndpoint {
    val HOSTS = listOf("api.eu.deepgram.com", "api.deepgram.com")

    private const val QUERY =
        "?model=nova-3&language=en&smart_format=true&punctuate=true" +
            "&interim_results=true&endpointing=300&encoding=linear16" +
            "&sample_rate=16000&channels=1"

    fun url(index: Int): String = "wss://${HOSTS[index]}/v1/listen$QUERY"

    fun shouldTryNextHost(index: Int, httpCode: Int?, opened: Boolean, stopping: Boolean): Boolean =
        !opened && !stopping && index + 1 < HOSTS.size && httpCode != 401 && httpCode != 403

    fun failureMessage(httpCode: Int?, cause: Throwable?): String = when {
        httpCode == 401 || httpCode == 403 -> "Deepgram rejected the API key"
        httpCode == 402 -> "Deepgram account has no credit left"
        httpCode == 429 -> "Deepgram is rate limiting this key, try again shortly"
        httpCode != null -> "Dictation connection failed (Deepgram HTTP $httpCode)"
        cause is java.net.UnknownHostException -> "Dictation could not find Deepgram, check the connection"
        cause is javax.net.ssl.SSLException -> "Dictation secure connection failed: ${cause.message}"
        cause != null -> "Dictation connection failed: ${cause.javaClass.simpleName}"
        else -> "Dictation connection failed"
    }
}
