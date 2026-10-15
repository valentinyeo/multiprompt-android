package dev.multiprompt.companion.sync

import java.security.MessageDigest
import java.security.SecureRandom

object RecoveryKey {

    const val KEY_BYTES = 32

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val PAYLOAD_SYMBOLS = 52
    private const val CHECKSUM_SYMBOLS = 4
    private const val GROUP_SIZE = 4

    fun generate(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    fun encode(key: ByteArray): String {
        require(key.size == KEY_BYTES) { "recovery key must be $KEY_BYTES bytes" }
        val symbols = encodePayload(key) + encodeChecksum(key)
        return symbols.chunked(GROUP_SIZE).joinToString("-")
    }

    fun decode(text: String): ByteArray {
        val symbols = text.uppercase().filterNot { it == '-' || it.isWhitespace() }
        if (symbols.length != PAYLOAD_SYMBOLS + CHECKSUM_SYMBOLS) badFormat("recovery key has the wrong length")
        symbols.forEach { if (ALPHABET.indexOf(it) < 0) badFormat("recovery key has an invalid character") }
        val key = decodePayload(symbols.substring(0, PAYLOAD_SYMBOLS))
        val checksum = symbols.substring(PAYLOAD_SYMBOLS)
        if (checksum != encodeChecksum(key)) badFormat("recovery key checksum does not match")
        return key
    }

    fun isValid(text: String): Boolean = runCatching { decode(text) }.isSuccess

    private fun encodePayload(key: ByteArray): String {
        val sb = StringBuilder(PAYLOAD_SYMBOLS)
        var buffer = 0L
        var bits = 0
        for (b in key) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[((buffer shr bits) and 0x1F).toInt()])
            }
            buffer = buffer and ((1L shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[((buffer shl (5 - bits)) and 0x1F).toInt()])
        return sb.toString()
    }

    private fun decodePayload(symbols: String): ByteArray {
        var buffer = 0L
        var bits = 0
        val out = ByteArray(KEY_BYTES)
        var outPos = 0
        for ((index, c) in symbols.withIndex()) {
            val value = ALPHABET.indexOf(c)
            if (index == symbols.length - 1 && value and 0x01 != 0) badFormat("recovery key has non-canonical padding")
            buffer = (buffer shl 5) or value.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[outPos++] = ((buffer shr bits) and 0xFF).toInt().toByte()
                buffer = buffer and ((1L shl bits) - 1)
            }
        }
        return out
    }

    private fun encodeChecksum(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        val value = ((digest[0].toInt() and 0xFF) shl 12) or ((digest[1].toInt() and 0xFF) shl 4)
        return "" +
            ALPHABET[(value shr 15) and 0x1F] +
            ALPHABET[(value shr 10) and 0x1F] +
            ALPHABET[(value shr 5) and 0x1F] +
            ALPHABET[value and 0x1F]
    }

    private fun badFormat(message: String): Nothing =
        throw SyncProtocol.SyncProtocolException(SyncProtocol.SyncProtocolException.Reason.BAD_RECOVERY_KEY_FORMAT, message)
}
