package dev.multiprompt.companion.room

import org.json.JSONObject

class RoomFailure(val code: String) : Exception(code)

interface RoomWire : AutoCloseable {
    suspend fun call(request: JSONObject): JSONObject
}

class LineAssembler(private val limit: Int = 131_072) {
    private val buffer = StringBuilder()
    private var dropped = false

    fun append(chunk: ByteArray, length: Int = chunk.size): List<String> {
        val finished = mutableListOf<String>()
        for (index in 0 until length) {
            val byte = chunk[index].toInt() and 0xFF
            when {
                dropped && byte == '\n'.code -> {
                    dropped = false
                    buffer.clear()
                }
                dropped -> Unit
                byte == '\n'.code -> {
                    finished += buffer.toString().trimEnd('\r')
                    buffer.clear()
                }
                else -> {
                    buffer.append(byte.toChar())
                    if (buffer.length > limit) {
                        dropped = true
                        buffer.clear()
                    }
                }
            }
        }
        return finished
    }
}
