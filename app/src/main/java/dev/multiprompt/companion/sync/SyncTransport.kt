package dev.multiprompt.companion.sync

interface SyncTransport {

    data class RemoteEntity(
        val entityId: String,
        val revision: Long,
        val tombstone: Boolean,
        val payload: ByteArray?,
    )

    data class PushResult(
        val revision: Long,
        val conflict: RemoteEntity?,
    )

    suspend fun list(recordId: String, sinceRevision: Long): List<RemoteEntity>

    suspend fun push(
        recordId: String,
        entityId: String,
        expectedRevision: Long,
        tombstone: Boolean,
        payload: ByteArray?,
    ): PushResult
}

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
