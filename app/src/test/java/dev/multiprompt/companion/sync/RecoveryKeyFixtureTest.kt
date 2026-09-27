package dev.multiprompt.companion.sync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Verifies the recovery-key addition (docs/sync-protocol-v1.md, "Recovery key") against
 * the committed fixture vectors byte-for-byte, the same contract as
 * [SyncProtocolFixtureTest] — these vectors are what the zigshell desktop side must
 * reproduce too.
 */
class RecoveryKeyFixtureTest {

    private fun doc(): JSONObject {
        val stream = javaClass.classLoader.getResourceAsStream(FIXTURE_RESOURCE)
            ?: error("fixture resource $FIXTURE_RESOURCE is missing from the test classpath")
        return JSONObject(stream.readBytes().decodeToString())
    }

    @Test
    fun envelopeWithRecoveryReproducesByteForByte() {
        val vectors = doc().getJSONArray("recoveryVectors")
        assertTrue("must contain at least one recovery vector", vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val record = v.getJSONArray("records").getJSONObject(0)
            val recordId = record.getString("id")
            val plaintext = record.getString("plaintextUtf8").toByteArray(Charsets.UTF_8)

            val envelope = SyncProtocol.sealWithRecovery(
                v.getString("passphrase").toCharArray(),
                mapOf(recordId to plaintext),
                b64(v.getString("salt")),
                b64(v.getString("vaultKey")),
                b64(v.getString("wrapIv")),
                mapOf(recordId to b64(record.getString("iv"))),
                b64(v.getString("recoveryKeyBytes")),
                b64(v.getString("recoverySalt")),
                b64(v.getString("recoveryIv")),
            )
            val envelopeText = envelope.toString(Charsets.UTF_8)

            assertEquals(name, v.getString("envelopeJson"), envelopeText)
            assertEquals(name, v.getString("envelopeSha256"), sha256Hex(envelope))
            assertEquals(name, v.getString("recoveryKeyString"), RecoveryKey.encode(b64(v.getString("recoveryKeyBytes"))))

            // Passphrase path still opens it, unaffected by the recovery block.
            val opened = SyncProtocol.open(v.getString("passphrase").toCharArray(), envelope)
            assertArrayEquals(name, plaintext, opened.getValue(recordId))
        }
    }

    @Test
    fun openWithRecoveryKeyMatchesThePassphrasePathAndRewrapsCleanly() {
        val vectors = doc().getJSONArray("recoveryVectors")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val name = v.getString("name")
            val envelope = v.getString("envelopeJson").toByteArray(Charsets.UTF_8)
            val recoveryKeyString = v.getString("recoveryKeyString")
            val vaultKey = b64(v.getString("vaultKey"))

            val result = SyncProtocol.openWithRecoveryKey(recoveryKeyString, envelope)
            assertArrayEquals(name, vaultKey, result.vaultKey)
            val record = v.getJSONArray("records").getJSONObject(0)
            assertArrayEquals(name, record.getString("plaintextUtf8").toByteArray(Charsets.UTF_8), result.records.getValue(record.getString("id")))

            // Rewrapping under a new passphrase changes only kdf/wrap; records and the
            // recovery block are copied through unchanged, and the new passphrase opens.
            val rewrapped = SyncProtocol.rewrapPassphraseWith(
                result.vaultKey,
                "a brand new passphrase".toCharArray(),
                b64("AAAAAAAAAAAAAAAAAAAAAA=="),
                b64("AAAAAAAAAAAAAAAA"),
                envelope,
            )
            val reopened = SyncProtocol.open("a brand new passphrase".toCharArray(), rewrapped)
            assertArrayEquals(name, record.getString("plaintextUtf8").toByteArray(Charsets.UTF_8), reopened.getValue(record.getString("id")))
            // Old passphrase no longer works after rewrap.
            org.junit.Assert.assertThrows(SyncProtocol.SyncProtocolException::class.java) {
                SyncProtocol.open(v.getString("passphrase").toCharArray(), rewrapped)
            }
            // The recovery key still opens the rewrapped envelope (untouched by rewrap).
            val stillRecovers = SyncProtocol.openWithRecoveryKey(recoveryKeyString, rewrapped)
            assertArrayEquals(name, vaultKey, stillRecovers.vaultKey)
        }
    }

    @Test
    fun wrongRecoveryKeyAndTamperedRecoveryBlockAreRejected() {
        val v = doc().getJSONArray("recoveryVectors").getJSONObject(0)
        val envelope = v.getString("envelopeJson").toByteArray(Charsets.UTF_8)

        // A well-formed, checksum-valid, but wrong recovery key.
        val wrongKeyBytes = b64(v.getString("recoveryKeyBytes")).copyOf().also { it[0] = it[0].inc() }
        val wrongKeyString = RecoveryKey.encode(wrongKeyBytes)
        val ex = org.junit.Assert.assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.openWithRecoveryKey(wrongKeyString, envelope)
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.WRONG_RECOVERY_KEY, ex.reason)

        // Tampering the recovery ciphertext fails the same way (the wrap tag, not a
        // separate "tampered" reason — indistinguishable from a wrong key by design,
        // mirroring the passphrase wrap).
        val envelopeText = v.getString("envelopeJson")
        val ctNeedle = JSONObject(envelopeText).getJSONObject("recovery").getString("ct")
        val pos = envelopeText.indexOf(ctNeedle)
        val flipped = (if (ctNeedle[0] == 'A') 'B' else 'A') + ctNeedle.substring(1)
        val tamperedText = envelopeText.substring(0, pos) + flipped + envelopeText.substring(pos + ctNeedle.length)
        val exTampered = org.junit.Assert.assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.openWithRecoveryKey(v.getString("recoveryKeyString"), tamperedText.toByteArray(Charsets.UTF_8))
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.WRONG_RECOVERY_KEY, exTampered.reason)

        // An envelope with no recovery block at all (the pre-existing fixtures).
        val noRecoveryEnvelope = doc().getJSONArray("vectors").getJSONObject(0).getString("envelopeJson")
        val exMissing = org.junit.Assert.assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            SyncProtocol.openWithRecoveryKey(v.getString("recoveryKeyString"), noRecoveryEnvelope.toByteArray(Charsets.UTF_8))
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.MALFORMED, exMissing.reason)
    }

    @Test
    fun recoveryKeyStringVectorsEncodeDecodeAndChecksum() {
        val vectors = doc().getJSONArray("recoveryKeyStringVectors")
        assertTrue(vectors.length() >= 2)
        val good = vectors.getJSONObject(0)
        assertFalse(good.getString("name").contains("bad"))
        val keyBytes = b64(good.getString("keyBytes"))
        assertEquals(good.getString("name"), good.getString("encoded"), RecoveryKey.encode(keyBytes))
        assertArrayEquals(good.getString("name"), keyBytes, RecoveryKey.decode(good.getString("encoded")))
        assertTrue(RecoveryKey.isValid(good.getString("encoded")))

        // Case-insensitivity and ignored whitespace/dashes round-trip identically.
        val messy = " " + good.getString("encoded").lowercase().replace("-", " - ") + " "
        assertArrayEquals(keyBytes, RecoveryKey.decode(messy))

        for (i in 1 until vectors.length()) {
            val bad = vectors.getJSONObject(i)
            assertFalse(bad.getString("name"), RecoveryKey.isValid(bad.getString("encoded")))
            val ex = org.junit.Assert.assertThrows(SyncProtocol.SyncProtocolException::class.java) {
                RecoveryKey.decode(bad.getString("encoded"))
            }
            assertEquals(bad.getString("name"), SyncProtocol.SyncProtocolException.Reason.BAD_RECOVERY_KEY_FORMAT, ex.reason)
        }
    }

    @Test
    fun malformedRecoveryKeyStringsAreRejected() {
        assertFalse(RecoveryKey.isValid("not even close"))
        assertFalse(RecoveryKey.isValid(""))
        val ex = org.junit.Assert.assertThrows(SyncProtocol.SyncProtocolException::class.java) {
            RecoveryKey.decode("too-short")
        }
        assertEquals(SyncProtocol.SyncProtocolException.Reason.BAD_RECOVERY_KEY_FORMAT, ex.reason)
    }

    @Test
    fun generatedKeyRoundTripsThroughEncodeDecodeAndSealNewWithRecovery() {
        val generated = RecoveryKey.generate()
        assertEquals(RecoveryKey.KEY_BYTES, generated.size)
        assertArrayEquals(generated, RecoveryKey.decode(RecoveryKey.encode(generated)))

        val sealed = SyncProtocol.sealNewWithRecovery(
            "a fresh vault passphrase".toCharArray(),
            mapOf("hosts" to "{\"hosts\":[]}".toByteArray(Charsets.UTF_8)),
        )
        val opened = SyncProtocol.openWithRecoveryKey(sealed.recoveryKey, sealed.envelope)
        assertArrayEquals(sealed.vaultKey, opened.vaultKey)
        assertArrayEquals("{\"hosts\":[]}".toByteArray(Charsets.UTF_8), opened.records.getValue("hosts"))
    }

    private fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIXTURE_RESOURCE = "sync/sync-protocol-v1-fixtures.json"
    }
}
