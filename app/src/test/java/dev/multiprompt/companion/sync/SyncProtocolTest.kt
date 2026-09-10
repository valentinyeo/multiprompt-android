package dev.multiprompt.companion.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/** Behavioural tests for the v1 envelope, independent of the cross-language fixtures. */
class SyncProtocolTest {

    private val passphrase = "correct horse battery staple".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }
    private val vaultKey = ByteArray(32) { (it * 3).toByte() }
    private val wrapIv = ByteArray(12) { (it + 1).toByte() }

    private fun fixedRecords(): Map<String, ByteArray> = mapOf(
        "hosts" to """{"hosts":[{"id":"h1","label":"vps"}]}""".toByteArray(),
        "sessionState" to """{"readKeys":[]}""".toByteArray(),
    )

    private fun fixedIvs(records: Map<String, ByteArray>): Map<String, ByteArray> = records.keys.associateWith { id ->
        ByteArray(12) { (it + id[0].code.toByte() + 64).toByte() }
    }

    @Test
    fun roundTripReturnsTheOriginalRecords() {
        val records = fixedRecords()
        val envelope = SyncProtocol.seal(passphrase, records)
        val opened = SyncProtocol.open(passphrase, envelope)
        assertEquals(records.keys, opened.keys)
        records.forEach { (id, plaintext) -> assertArrayEquals(plaintext, opened.getValue(id)) }
    }

    @Test
    fun sealIsDeterministicForFixedInputs() {
        val records = fixedRecords()
        val first = SyncProtocol.sealWith(passphrase, records, salt, vaultKey, wrapIv, fixedIvs(records))
        val second = SyncProtocol.sealWith(passphrase, records, salt, vaultKey, wrapIv, fixedIvs(records))
        assertArrayEquals(first, second)
    }

    @Test
    fun sealUsesFreshRandomnessPerCall() {
        val records = fixedRecords()
        val first = SyncProtocol.seal(passphrase, records)
        val second = SyncProtocol.seal(passphrase, records)
        assertNotEquals(first, second)
        // Both must still open correctly.
        records.forEach { (id, plaintext) ->
            assertArrayEquals(plaintext, SyncProtocol.open(passphrase, first).getValue(id))
            assertArrayEquals(plaintext, SyncProtocol.open(passphrase, second).getValue(id))
        }
    }

    @Test
    fun wrongPassphraseFailsAtTheWrap() {
        val envelope = SyncProtocol.seal(passphrase, fixedRecords())
        val exception = assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.open("wrong passphrase".toCharArray(), envelope)
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.WRONG_PASSPHRASE, exception.reason)
    }

    @Test
    fun tamperedRecordFailsWithTampered() {
        val envelope = SyncProtocol.seal(passphrase, fixedRecords())
        val corrupted = flipLastRecordCiphertext(String(envelope, Charsets.UTF_8))
        val exception = assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.open(passphrase, corrupted.toByteArray(Charsets.UTF_8))
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.TAMPERED, exception.reason)
    }

    @Test
    fun renamingARecordFailsBecauseTheIdIsBoundByAad() {
        val envelope = SyncProtocol.seal(passphrase, fixedRecords())
        val renamed = String(envelope, Charsets.UTF_8).replace("\"hosts\":", "\"hostz\":")
        val exception = assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.open(passphrase, renamed.toByteArray(Charsets.UTF_8))
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.TAMPERED, exception.reason)
    }

    @Test
    fun tamperedKdfParametersFailTheWrap() {
        val envelope = SyncProtocol.seal(passphrase, fixedRecords())
        val text = String(envelope, Charsets.UTF_8)
        val corrupted = text.replace("\"memoryKiB\":65536", "\"memoryKiB\":65537")
        assertNotEquals(text, corrupted)
        val exception = assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.open(passphrase, corrupted.toByteArray(Charsets.UTF_8))
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.WRONG_PASSPHRASE, exception.reason)
    }

    @Test
    fun unknownEnvelopeVersionIsRejected() {
        val envelope = SyncProtocol.seal(passphrase, fixedRecords())
        val bumped = String(envelope, Charsets.UTF_8).replace("\"v\":1,", "\"v\":2,")
        val exception = assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.open(passphrase, bumped.toByteArray(Charsets.UTF_8))
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.UNSUPPORTED_VERSION, exception.reason)
    }

    @Test
    fun malformedEnvelopeIsRejected() {
        val exception = assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.open(passphrase, "not json at all".toByteArray(Charsets.UTF_8))
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.MALFORMED, exception.reason)
    }

    /** Corrupt one byte of the final record's ciphertext, keeping the canonical shape. */
    private fun flipLastRecordCiphertext(envelope: String): String {
        val marker = "\"ct\":\""
        val start = envelope.lastIndexOf(marker) + marker.length
        val end = envelope.indexOf('"', start)
        val flipped = flipBase64(envelope.substring(start, end))
        return envelope.substring(0, start) + flipped + envelope.substring(end)
    }

    private fun flipBase64(value: String): String {
        val decoded = Base64.getDecoder().decode(value)
        decoded[0] = (decoded[0].toInt() xor 0x01).toByte()
        return Base64.getEncoder().encodeToString(decoded)
    }
}
