package dev.multiprompt.companion.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * End-to-end JVM tests of the sync model: schema goldens, canonical sealing, merge and
 * tombstone rules, and the mocked transport's server semantics — the whole entity
 * pipeline without any Cloudflare dependency.
 */
class SyncPipelineTest {

    private val vaultKey = ByteArray(32) { (it * 5 + 1).toByte() }
    private val accountId = "acc-test"

    private fun transport() = InMemorySyncTransport()

    private fun seal(recordId: String, entityId: String, revision: Long, body: String): ByteArray =
        SyncProtocol.sealEntity(
            vaultKey,
            SyncProtocol.EntityMetadata(recordId, entityId, accountId, revision),
            body.toByteArray(Charsets.UTF_8),
            deterministicIv(recordId, entityId, revision),
        )

    private fun deterministicIv(recordId: String, entityId: String, revision: Long): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$accountId/$recordId/$entityId/$revision".toByteArray(Charsets.UTF_8))
        return hash.copyOf(12)
    }

    // --- schema goldens ------------------------------------------------------------------

    @Test
    fun mappersReproduceTheCommittedSchemaGoldens() {
        val doc = fixtureDoc()
        val schemaVectors = doc.getJSONArray("schemaVectors")
        for (i in 0 until schemaVectors.length()) {
            val vector = schemaVectors.getJSONObject(i)
            val name = vector.getString("name")
            val expected = vector.getString("canonicalBodyJson")
            val actual = when (vector.getString("recordId")) {
                "hosts" -> {
                    val hosts = SyncSchema.parseHosts(expected)
                    SyncSchema.hostsBody(hosts)
                }
                "workspaces" -> {
                    val workspaces = SyncSchema.parseWorkspaces(expected)
                    SyncSchema.workspacesBody(workspaces)
                }
                else -> {
                    val state = SyncSchema.parseSessionState(expected)
                    SyncSchema.sessionStateBody(state)
                }
            }
            assertEquals(name, expected, actual)
        }
    }

    @Test
    fun hostsWithCaseVariantLabelsSortCaseInsensitivelyThenById() {
        val body = SyncSchema.hostsBody(
            listOf(
                SyncSchema.SyncHost(id = "b", label = "Beta", hostname = "b", port = 22, username = "u", keySecretId = "k2"),
                SyncSchema.SyncHost(id = "a", label = "alpha", hostname = "a", port = 22, username = "u", keySecretId = "k1"),
            ),
        )
        val order = SyncSchema.parseHosts(body).map { it.id }
        assertEquals(listOf("a", "b"), order)
    }

    @Test
    fun hostBodyNeverCarriesSecretMaterial() {
        val body = SyncSchema.hostsBody(
            listOf(
                SyncSchema.SyncHost(
                    id = "h", label = "l", hostname = "h", port = 22, username = "u",
                    keySecretId = "key-1",
                ),
            ),
        )
        org.junit.Assert.assertFalse(body.contains("BEGIN OPENSSH PRIVATE KEY"))
        org.junit.Assert.assertFalse(body.lowercase().contains("private key"))
        // The local-only reference serializes as its id; an absent passphrase reference
        // serializes as null per the schema goldens.
        assertEquals(true, body.contains("\"keySecretId\":\"key-1\""))
        assertEquals(true, body.contains("\"passphraseSecretId\":null"))
    }

    // --- entity ids ------------------------------------------------------------------------

    @Test
    fun sessionEntityIdIsHostUuidPlusEncodedTmuxName() {
        val id = SyncSchema.sessionEntityId("3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31", "my-session")
        assertEquals("3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31--my-session", id)
        assertEquals("3f7c1b2e-8a4d-4c6e-9b2f-1d5a7c9e0b31" to "my-session", SyncSchema.sessionIdFrom(id))
    }

    @Test
    fun sessionEntityIdPercentEncodesNamesOutsideTheAlphabet() {
        val id = SyncSchema.sessionEntityId("h1", "weird/name:with spaces")
        // Slashes and colons are outside ^[A-Za-z0-9][A-Za-z0-9._-]*$
        val regex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        assertEquals(true, Regex("^[A-Za-z0-9][A-Za-z0-9._%*-]*$").matches(id))
        val decoded = SyncSchema.sessionIdFrom(id)!!
        assertEquals("h1", decoded.first)
        assertEquals("weird/name:with spaces", java.net.URLDecoder.decode(id.substringAfter("--"), "UTF-8"))
    }

    // --- merge / tombstones ----------------------------------------------------------------

    @Test
    fun sessionStateMergeKeepsTheWatermarkMonotonic() {
        val stale = SyncMerge.LocalEntity(
            "sessionState", "h--s",
            bodyJson = SyncSchema.sessionStateBody(SyncSchema.SyncSessionState(lastReadAt = 100)),
            revision = 9,
        )
        val remote = SyncMerge.LocalEntity(
            "sessionState", "h--s",
            bodyJson = SyncSchema.sessionStateBody(SyncSchema.SyncSessionState(lastReadAt = 500)),
            revision = 7,
        )
        val result = SyncMerge.rebase(stale, remote)
        // The stale device cannot regress the watermark: merged body has 500 whether the
        // result is Applied (something to push) or NoOp (remote already has it).
        val body = when (result) {
            is SyncMerge.MergeResult.Applied -> result.body
            is SyncMerge.MergeResult.NoOp -> result.body
            SyncMerge.MergeResult.Dropped -> error("watermark merge must not drop")
        }
        assertEquals(500, SyncSchema.parseSessionState(body).lastReadAt)
    }

    @Test
    fun tombstoneCannotBeResurrectedByADelayedWrite() {
        val transport = transport()
        transport.seed("hosts", "h1", revision = 3, tombstone = true)
        val result = runBlocking {
            transport.push("hosts", "h1", expectedRevision = 0, tombstone = false, payload = seal("hosts", "h1", 1, "{}"))
        }
        assertEquals(3L, result.revision)
        assertEquals(true, result.conflict?.tombstone == true)
    }

    @Test
    fun conflictCarriesTheCurrentRowForRebase() {
        val transport = transport()
        transport.seed("hosts", "h1", revision = 5, payload = seal("hosts", "h1", 5, "{}"))
        val result = runBlocking {
            transport.push("hosts", "h1", expectedRevision = 4, tombstone = false, payload = seal("hosts", "h1", 4, "{}"))
        }
        assertNotNull(result.conflict)
        assertEquals(5L, result.conflict!!.revision)
    }

    @Test
    fun successfulPushBumpsTheRevisionMonotonically() {
        val transport = transport()
        val first = runBlocking { transport.push("hosts", "h1", 0, false, seal("hosts", "h1", 0, "{}")) }
        val second = runBlocking { transport.push("hosts", "h1", first.revision, false, seal("hosts", "h1", first.revision, "{}")) }
        assertEquals(1L, first.revision)
        assertEquals(2L, second.revision)
    }

    @Test
    fun sealedEntityRoundTripsThroughTheTransportAndBackToPlaintext() {
        val transport = transport()
        val body = SyncSchema.sessionStateBody(SyncSchema.SyncSessionState(lastReadAt = 100))
        // The payload is sealed for the revision it will occupy: expectedRevision + 1.
        val payload = seal("sessionState", "h--s", 1, body)
        val pushed = runBlocking { transport.push("sessionState", "h--s", 0, false, payload) }
        val listed = runBlocking { transport.list("sessionState", 0) }
        assertEquals(1, pushed.revision)
        assertEquals(1, listed.size)
        val metadata = SyncProtocol.EntityMetadata("sessionState", "h--s", accountId, listed[0].revision)
        assertEquals(body, String(SyncProtocol.openEntity(vaultKey, metadata, listed[0].payload!!), Charsets.UTF_8))
    }

    private fun fixtureDoc(): org.json.JSONObject {
        val stream = javaClass.classLoader.getResourceAsStream("sync/sync-protocol-v1-fixtures.json")!!
        return org.json.JSONObject(stream.readBytes().decodeToString())
    }
}
