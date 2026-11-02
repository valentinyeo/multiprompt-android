package dev.multiprompt.companion.computer

data class ComputerTarget(val host: String = DEFAULT_HOST, val port: Int = DEFAULT_PORT) {
    val value: String get() = "$host:$port"

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 6080
        val Default = ComputerTarget()

        fun parse(value: String?): ComputerTarget? {
            val text = (value ?: "").trim()
                .removeSuffix("/")
                .removePrefix("http://")
                .removePrefix("https://")
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            if (!text.contains(':')) return ComputerTarget(text, DEFAULT_PORT)
            val host = text.substringBeforeLast(':')
            val port = text.substringAfterLast(':').toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            return ComputerTarget(host, port)
        }
    }
}
