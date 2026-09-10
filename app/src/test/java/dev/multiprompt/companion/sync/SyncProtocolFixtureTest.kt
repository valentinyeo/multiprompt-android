package dev.multiprompt.companion.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * Verifies the Kotlin implementation against the committed, cross-language fixture
 * vectors byte-for-byte. These vectors are the contract the zigshell desktop side must
 * reproduce; see docs/sync-protocol-v1.md.
 */
class SyncProtocolFixtureTest {

    private fun vectors(): JSONArray {
        val stream = javaClass.classLoader.getResourceAsStream(FIXTURE_RESOURCE)
            ?: error("fixture resource $FIXTURE_RESOURCE is missing from the test classpath")
        val doc = JSONObject(stream.readBytes().decodeToString())
        assertEquals("multiprompt-sync-v1", doc.getString("protocol"))
        return doc.getJSONArray("vectors")
    }

    @Test
    fun fixturesExistAndContainVectors() {
        assertTrue("fixture file must contain at least one vector", vectors().length() > 0)
    }

    @Test
    fun everyVectorReproducesTheEnvelopeByteForByte() {
        val vectors = vectors()
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val name = vector.getString("name")
            val records = linkedMapOf<String, ByteArray>()
            val recordIvs = linkedMapOf<String, ByteArray>()
            val expectedPlaintexts = linkedMapOf<String, ByteArray>()
            val recordArray = vector.getJSONArray("records")
            for (j in 0 until recordArray.length()) {
                val record = recordArray.getJSONObject(j)
                val id = record.getString("id")
                val plaintext = record.getString("plaintextUtf8").toByteArray(Charsets.UTF_8)
                records[id] = plaintext
                recordIvs[id] = b64(record.getString("iv"))
                expectedPlaintexts[id] = plaintext
            }

            val envelope = SyncProtocol.sealWith(
                vector.getString("passphrase").toCharArray(),
                records,
                b64(vector.getString("salt")),
                b64(vector.getString("vaultKey")),
                b64(vector.getString("wrapIv")),
                recordIvs,
            )
            val envelopeText = envelope.toString(Charsets.UTF_8)

            assertEquals(name, vector.getString("envelopeJson"), envelopeText)
            assertEquals(name, vector.getString("envelopeSha256"), sha256Hex(envelope))
            assertEquals(
                name,
                vector.getString("wrapCt"),
                JSONObject(envelopeText).getJSONObject("wrap").getString("ct"),
            )
            for (j in 0 until recordArray.length()) {
                val record = recordArray.getJSONObject(j)
                val ct = JSONObject(envelopeText).getJSONObject("records").getJSONObject(record.getString("id")).getString("ct")
                assertEquals(name, record.getString("ct"), ct)
            }

            val opened = SyncProtocol.open(vector.getString("passphrase").toCharArray(), envelope)
            assertEquals(name, records.keys, opened.keys)
            records.forEach { (id, plaintext) -> assertArrayEquals(name, plaintext, opened[id]) }
        }
    }

    @Test
    fun everyEntityVectorReproducesThePayloadByteForByte() {
        val vectors = vectors()
        for (i in 0 until vectors.length()) {
            val doc = vectors.getJSONObject(i)
            if (!doc.has("entityVectors")) continue
            val entityVectors = doc.getJSONArray("entityVectors")
            for (j in 0 until entityVectors.length()) {
                val vector = entityVectors.getJSONObject(j)
                val name = vector.getString("name")
                val metadata = SyncProtocol.EntityMetadata(
                    recordId = vector.getString("recordId"),
                    entityId = vector.getString("entityId"),
                    accountId = vector.getString("accountId"),
                    revision = vector.getLong("revision"),
                    schemaVersion = vector.optInt("schemaVersion", SyncProtocol.VERSION),
                )
                val payload = SyncProtocol.sealEntity(
                    b64(vector.getString("vaultKey")),
                    metadata,
                    vector.getString("plaintextUtf8").toByteArray(Charsets.UTF_8),
                    b64(vector.getString("iv")),
                )
                assertEquals(name, vector.getString("payloadJson"), payload.toString(Charsets.UTF_8))
                assertEquals(name, vector.getString("payloadSha256"), sha256Hex(payload))
                assertArrayEquals(
                    name,
                    vector.getString("plaintextUtf8").toByteArray(Charsets.UTF_8),
                    SyncProtocol.openEntity(b64(vector.getString("vaultKey")), metadata, payload),
                )
            }
        }
    }

    private fun b64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIXTURE_RESOURCE = "sync/sync-protocol-v1-fixtures.json"
    }
}
