package dev.multiprompt.companion.sync

/**
 * Transport for sync protocol v1 entity records. This is the only seam the production
 * Cloudflare Worker/D1 implementation will plug into — it does not exist yet, and no
 * Cloudflare provisioning or production auth happens until the account/zone/domain and
 * the auth spike are settled (IGSH-223, milestone M0).
 *
 * The transport speaks canonical entity-record payloads ([SyncProtocol.sealEntity] /
 * [SyncProtocol.openEntity]) plus server revisions and tombstones. It knows nothing about
 * entities' contents.
 */
interface SyncTransport {

    data class RemoteEntity(
        val entityId: String,
        val revision: Long,
        val tombstone: Boolean,
        /** Canonical entity-record payload (sealed), or null for a tombstone stub. */
        val payload: ByteArray?,
    )

    data class PushResult(
        /** New server-assigned revision after a successful write. */
        val revision: Long,
        /**
         * Non-null when the push was rejected (optimistic-concurrency conflict): the
         * server's current row. The client rebases via [SyncMerge.rebase] and retries.
         */
        val conflict: RemoteEntity?,
    )

    /** Lists every entity for [recordId] at or above [sinceRevision] (0 = all). */
    suspend fun list(recordId: String, sinceRevision: Long): List<RemoteEntity>

    /**
     * Pushes one entity: [recordId], [entityId], [expectedRevision] (0 = create), sealed
     * [payload]. Returns the new revision, or a conflict carrying the server's current
     * row when the expected revision no longer matches.
     */
    suspend fun push(
        recordId: String,
        entityId: String,
        expectedRevision: Long,
        tombstone: Boolean,
        payload: ByteArray?,
    ): PushResult
}

/**
 * In-memory fake implementing the server semantics of sync protocol v1: per-key
 * monotonic revisions, optimistic concurrency (409-style conflicts with the current
 * row), and tombstones that a delayed write cannot resurrect. Used by the JVM tests and
 * as the reference for the later Worker/D1 implementation.
 */
class InMemorySyncTransport : SyncTransport {

    data class Row(
        val recordId: String,
        val entityId: String,
        val revision: Long,
        val tombstone: Boolean,
        val payload: ByteArray?,
    )

    private val rows = linkedMapOf<Pair<String, String>, Row>()
    private val revisionCounters = linkedMapOf<Pair<String, String>, Long>()

    /** Sealed payloads are stored verbatim; the fake never decrypts. */
    fun seed(recordId: String, entityId: String, revision: Long, tombstone: Boolean = false, payload: ByteArray? = null) {
        rows[recordId to entityId] = Row(recordId, entityId, revision, tombstone, payload)
        revisionCounters[recordId to entityId] = revision
    }

    fun allRows(): List<Row> = rows.values.toList()

    override suspend fun list(recordId: String, sinceRevision: Long): List<SyncTransport.RemoteEntity> =
        rows.values
            .filter { it.recordId == recordId && it.revision >= sinceRevision }
            .map { SyncTransport.RemoteEntity(it.entityId, it.revision, it.tombstone, it.payload) }

    override suspend fun push(
        recordId: String,
        entityId: String,
        expectedRevision: Long,
        tombstone: Boolean,
        payload: ByteArray?,
    ): SyncTransport.PushResult {
        val key = recordId to entityId
        val current = rows[key]
        if (current != null && current.revision != expectedRevision) {
            return SyncTransport.PushResult(
                revision = current.revision,
                conflict = SyncTransport.RemoteEntity(current.entityId, current.revision, current.tombstone, current.payload),
            )
        }
        if (current != null && current.tombstone && !tombstone) {
            return SyncTransport.PushResult(
                revision = current.revision,
                conflict = SyncTransport.RemoteEntity(current.entityId, current.revision, true, current.payload),
            )
        }
        val nextRevision = nextRevision(key)
        rows[key] = Row(recordId, entityId, nextRevision, tombstone, payload)
        return SyncTransport.PushResult(revision = nextRevision, conflict = null)
    }

    private fun nextRevision(key: Pair<String, String>): Long {
        val next = (revisionCounters[key] ?: 0L) + 1
        revisionCounters[key] = next
        return next
    }
}
