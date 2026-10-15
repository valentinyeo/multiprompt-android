package dev.multiprompt.companion.sync

object SyncMerge {

    data class LocalEntity(
        val recordId: String,
        val entityId: String,
        val bodyJson: String,
        val revision: Long,
        val tombstone: Boolean = false,
    )

    sealed class MergeResult {
        data class Applied(val body: String) : MergeResult()

        data class NoOp(val body: String) : MergeResult()

        data object Dropped : MergeResult()
    }

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

        val lastReadAt = maxOf(localState.lastReadAt, remoteState.lastReadAt)

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
