package dev.multiprompt.companion.dictation

/**
 * Deepgram hosts to try, in order. The EU regional host is faster from Europe (the desktop uses
 * it), but when it cannot be reached or refuses the handshake for any reason other than a bad
 * key, dictation used to die with "connection failed". The global host is the fallback.
 */
object DeepgramEndpoint {
    val HOSTS = listOf("api.eu.deepgram.com", "api.deepgram.com")

    private const val QUERY =
        "?model=nova-3&language=en&smart_format=true&punctuate=true" +
            "&interim_results=true&endpointing=300&encoding=linear16" +
            "&sample_rate=16000&channels=1"

    fun url(index: Int): String = "wss://${HOSTS[index]}/v1/listen$QUERY"

    /** A bad key fails the same way on every host; anything else is worth the next host. */
    fun shouldTryNextHost(index: Int, httpCode: Int?, opened: Boolean, stopping: Boolean): Boolean =
        !opened && !stopping && index + 1 < HOSTS.size && httpCode != 401 && httpCode != 403

    /** Says what actually went wrong, so the phone shows a cause instead of a generic line. */
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
