package dev.multiprompt.companion.reader

internal class SnapshotFingerprint {
    private var length = -1
    private var value = 0L

    fun same(reply: String): Boolean {
        var hash = BASIS
        var count = 0
        var index = 0
        while (index < reply.length) {
            val character = reply[index]
            val marker = when {
                character == '"' && reply.startsWith(REPLY_ID, index) -> REPLY_ID
                character == '@' && reply.startsWith(WIRE_ID, index) -> WIRE_ID
                else -> null
            }
            if (marker == null) {
                hash = mix(hash, character)
                count++
                index++
                continue
            }
            for (letter in marker) {
                hash = mix(hash, letter)
                count++
            }
            index += marker.length
            while (index < reply.length && reply[index].isDigit()) index++
        }
        val unchanged = count == length && hash == value
        length = count
        value = hash
        return unchanged
    }

    private fun mix(hash: Long, character: Char): Long = (hash xor character.code.toLong()) * PRIME

    private companion object {
        const val BASIS = -3750763034362895579L
        const val PRIME = 1099511628211L
        const val REPLY_ID = "\"id\":"
        const val WIRE_ID = "@mp\\t"
    }
}
