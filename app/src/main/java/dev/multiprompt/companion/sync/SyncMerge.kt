package dev.multiprompt.companion.sync

/**
 * Merge/tombstone semantics of sync protocol v1, per record id
 * (docs/sync-protocol-v1.md, "Optimistic concurrency" and "sessionState semantics").
 *
 * Conflicts resolve by rejection-and-rebase: on a 409 the client re-applies its logical
 * change on top of the server's winning entity via [rebase]. Wall-clock time is never
 * consulted; ordering authority is the server-assigned revision, carried in
 * [LocalEntity.revision].
 */
object SyncMerge {

    /** Local view of one entity: its canonical body and the revision we last saw. */
    data class LocalEntity(
        val recordId: String,
        val entityId: String,
        val bodyJson: String,
        val revision: Long,
        val tombstone: Boolean = false,
    )

    sealed class MergeResult {
        /** The rebased local body to push again (with the remote's revision as base). */
        data class Applied(val body: String) : MergeResult()

        /** The remote body already contains the local change; nothing to push. */
        data class NoOp(val body: String) : MergeResult()

        /** Nothing to do: the change is moot (e.g. deleting an already-deleted entity). */
        data object Dropped : MergeResult()
    }

    /**
     * Rebases the local [local] change on top of the server's winning [remote] entity.
     *
     * - hosts / workspaces: the local body is a whole-entity upsert (one host/workspace
     *   per entity row); re-applying keeps the local field values, because the server
     *   holds no other writer's fields for this entity id — but a *newer remote
     *   tombstone* wins.
     * - sessionState: per-state-key merge — lastReadAt = max (watermark); archive intent
     *   follows the higher revision; restore (local null archive) is authoritative when
     *   the local revision is newer.
     * - A tombstone on either side is authoritative: the entity stays deleted.
     */
    fun rebase(local: LocalEntity, remote: LocalEntity): MergeResult {
        require(local.recordId == remote.recordId && local.entityId == remote.entityId) {
            "rebase needs the same entity on both sides"
        }
        if (remote.tombstone) return MergeResult.Dropped
        if (local.tombstone) {
            return if (local.revision >= remote.revision) MergeResult.Applied(local.bodyJson) else MergeResult.Dropped
        }
        if (remote.revision == local.revision) return MergeResult.NoOp(remote.bodyJson)

        return when (local.recordId) {
            "sessionState" -> rebaseSessionState(local, remote)
            "hosts", "workspaces" -> MergeResult.Applied(local.bodyJson)
            else -> error("unknown record id ${local.recordId}")
        }
    }

    private fun rebaseSessionState(local: LocalEntity, remote: LocalEntity): MergeResult {
        val localState = SyncSchema.parseSessionState(local.bodyJson)
        val remoteState = SyncSchema.parseSessionState(remote.bodyJson)

        // Watermark: monotonic max — a stale device can never pull it backwards.
        val lastReadAt = maxOf(localState.lastReadAt, remoteState.lastReadAt)

        // Archive intent: whichever side has the newer revision owns the archive state.
        // A local restore (archivedAt = null) with a newer revision is an authoritative
        // un-archive; an older local archive loses to the newer remote intent.
        val (archivedAt, resumeAt) = if (local.revision > remote.revision) {
            localState.archivedAt to localState.resumeAt
        } else {
            remoteState.archivedAt to remoteState.resumeAt
        }

        val merged = SyncSchema.SyncSessionState(
            lastReadAt = lastReadAt,
            archivedAt = archivedAt,
            resumeAt = if (archivedAt != null) resumeAt else null,
        )
        val body = SyncSchema.sessionStateBody(merged)
        return if (body == remote.bodyJson) MergeResult.NoOp(body) else MergeResult.Applied(body)
    }
}
