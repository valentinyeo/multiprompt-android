package dev.multiprompt.companion.sync

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Recovery key encoding for multiprompt sync protocol v1 — see the "Recovery key" section
 * of docs/sync-protocol-v1.md. A 256-bit CSPRNG value, shown to the user once as a
 * human-typeable Crockford Base32 string with a checksum group, so a mistyped character
 * is caught before it silently fails to unwrap the vault.
 *
 * The recovery key never goes to the server in any form (see [SyncProtocol.sealWithRecovery]).
 */
object RecoveryKey {

    const val KEY_BYTES = 32

    // Crockford Base32: 0-9 and A-Z minus I, L, O, U (visually ambiguous with 1/1/0/V).
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val PAYLOAD_SYMBOLS = 52 // ceil(256 bits / 5 bits per symbol)
    private const val CHECKSUM_SYMBOLS = 4
    private const val GROUP_SIZE = 4

    /** Generates a fresh 256-bit recovery key from a CSPRNG. */
    fun generate(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Encodes [key] (32 raw bytes) as a dash-grouped, case-insensitive, human-typeable
     * string: 52 Crockford Base32 symbols for the key itself, plus a 4-symbol checksum,
     * grouped every 4 characters (14 groups total, e.g. "XXXX-XXXX-...-XXXX").
     */
    fun encode(key: ByteArray): String {
        require(key.size == KEY_BYTES) { "recovery key must be $KEY_BYTES bytes" }
        val symbols = encodePayload(key) + encodeChecksum(key)
        return symbols.chunked(GROUP_SIZE).joinToString("-")
    }

    /**
     * Decodes a user-typed recovery key string back into 32 raw bytes. Whitespace and
     * dashes are ignored wherever they appear; every other character is compared
     * case-insensitively against the Crockford alphabet above — a character outside that
     * alphabet (including I, L, O, U) is rejected rather than corrected. Throws
     * [SyncProtocol.SyncProtocolException] with reason `BAD_RECOVERY_KEY_FORMAT` on a
     * wrong length, an unknown character, or a failed checksum (almost always a typo).
     */
    fun decode(text: String): ByteArray {
        val symbols = text.uppercase().filterNot { it == '-' || it.isWhitespace() }
        if (symbols.length != PAYLOAD_SYMBOLS + CHECKSUM_SYMBOLS) badFormat("recovery key has the wrong length")
        symbols.forEach { if (ALPHABET.indexOf(it) < 0) badFormat("recovery key has an invalid character") }
        val key = decodePayload(symbols.substring(0, PAYLOAD_SYMBOLS))
        val checksum = symbols.substring(PAYLOAD_SYMBOLS)
        if (checksum != encodeChecksum(key)) badFormat("recovery key checksum does not match")
        return key
    }

    /** True when [text] is a well-formed recovery key whose checksum matches. */
    fun isValid(text: String): Boolean = runCatching { decode(text) }.isSuccess

    // 256 bits -> 52 five-bit symbols; the last symbol's low bit is zero padding.
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

    // Inverse of encodePayload: 52 five-bit symbols -> 32 bytes. The final symbol's low
    // bit must be the zero padding encodePayload always emits; anything else means the
    // string was corrupted or hand-edited into a non-canonical form.
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

    // Checksum: the top 16 bits of SHA-256(key), packed into a 20-bit value (low 4 bits
    // zero) so it also encodes as exactly 4 Crockford symbols. Catches a mistyped
    // character; it is not a security boundary.
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
