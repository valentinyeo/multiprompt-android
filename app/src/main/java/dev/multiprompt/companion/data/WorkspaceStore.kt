package dev.multiprompt.companion.data

import android.content.Context
import dev.multiprompt.companion.model.TmuxSession
import dev.multiprompt.companion.model.Workspace
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class WorkspaceStore(context: Context) {
    private val preferences = context.getSharedPreferences("workspaces", Context.MODE_PRIVATE)

    fun load(): List<Workspace> {
        val raw = preferences.getString(KEY_WORKSPACES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        Workspace(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            hostId = item.getString("hostId"),
                            remotePath = item.getString("remotePath"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(workspace: Workspace) {
        save(load().filterNot { it.id == workspace.id } + workspace)
    }

    fun ordered(
        workspaces: List<Workspace>,
        sessionCounts: Map<String, Int>,
    ): List<Workspace> = orderWorkspaces(workspaces, sessionCounts, loadSplitOrder())

    fun splitIds(
        workspaces: List<Workspace>,
        latestActivityByWorkspace: Map<String, Long>,
    ): List<String?> = orderSplitIds(workspaces, latestActivityByWorkspace, loadSplitOrder())

    fun moveSplit(splitIds: List<String?>, splitId: String?, delta: Int): List<String?> {
        val currentIndex = splitIds.indexOf(splitId)
        if (currentIndex < 0) return splitIds
        val targetIndex = (currentIndex + delta).coerceIn(splitIds.indices)
        if (targetIndex == currentIndex) return splitIds
        val reordered = splitIds.toMutableList().apply {
            add(targetIndex, removeAt(currentIndex))
        }
        saveSplitOrder(reordered.map { it ?: ALL_SPLIT_ID })
        return reordered
    }

    fun resetSplitOrder(
        workspaces: List<Workspace>,
        latestActivityByWorkspace: Map<String, Long>,
    ): List<String?> {
        preferences.edit().remove(KEY_SPLIT_ORDER).apply()
        return orderSplitIds(workspaces, latestActivityByWorkspace, null)
    }

    fun discover(sessions: List<TmuxSession>): List<Workspace> {
        val current = load().toMutableList()
        var changed = false
        sessions.forEach { session ->
            val root = projectRoot(session.workingDirectory) ?: return@forEach
            if (current.none { it.hostId == session.hostId && it.remotePath == root }) {
                current += Workspace(
                    id = UUID.nameUUIDFromBytes("${session.hostId}::$root".toByteArray()).toString(),
                    name = root.substringAfterLast('/'),
                    hostId = session.hostId,
                    remotePath = root,
                )
                changed = true
            }
        }
        if (changed) save(current)
        return current.sortedBy { it.name.lowercase() }
    }

    fun workspaceIdFor(session: TmuxSession, workspaces: List<Workspace>): String? {
        val sessionKey = SessionReadStore.key(session.hostId, session.name)
        preferences.getString("$ASSIGNMENT_PREFIX$sessionKey", null)?.let { assigned ->
            if (workspaces.any { it.id == assigned }) return assigned
        }
        return workspaces
            .asSequence()
            .filter { workspace ->
                workspace.hostId == session.hostId &&
                    (session.workingDirectory == workspace.remotePath ||
                        session.workingDirectory.startsWith("${workspace.remotePath}/"))
            }
            .maxByOrNull { it.remotePath.length }
            ?.id
    }

    fun assign(session: TmuxSession, workspaceId: String) {
        val key = SessionReadStore.key(session.hostId, session.name)
        preferences.edit().putString("$ASSIGNMENT_PREFIX$key", workspaceId).apply()
    }

    fun removeHost(hostId: String) {
        val remaining = load().filterNot { it.hostId == hostId }
        val validIds = remaining.mapTo(mutableSetOf()) { it.id }
        val editor = preferences.edit()
        preferences.all.filterKeys { it.startsWith(ASSIGNMENT_PREFIX) }
            .filterValues { it !in validIds }
            .keys
            .forEach(editor::remove)
        loadSplitOrder()?.let { order ->
            editor.putString(
                KEY_SPLIT_ORDER,
                encodeIds(order.filter { it == ALL_SPLIT_ID || it in validIds }),
            )
        }
        editor.putString(KEY_WORKSPACES, encode(remaining)).apply()
    }

    private fun loadSplitOrder(): List<String>? {
        val raw = preferences.getString(KEY_SPLIT_ORDER, null) ?: return null
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }.getOrNull()
    }

    private fun saveSplitOrder(ids: List<String>) {
        preferences.edit().putString(KEY_SPLIT_ORDER, encodeIds(ids)).apply()
    }

    private fun encodeIds(ids: List<String>): String {
        val array = JSONArray()
        ids.forEach(array::put)
        return array.toString()
    }

    private fun save(workspaces: List<Workspace>) {
        preferences.edit().putString(KEY_WORKSPACES, encode(workspaces)).apply()
    }

    private fun encode(workspaces: List<Workspace>): String {
        val array = JSONArray()
        workspaces.sortedBy { it.name.lowercase() }.forEach { workspace ->
            array.put(
                JSONObject()
                    .put("id", workspace.id)
                    .put("name", workspace.name)
                    .put("hostId", workspace.hostId)
                    .put("remotePath", workspace.remotePath),
            )
        }
        return array.toString()
    }

    companion object {
        private const val KEY_WORKSPACES = "workspaces_json"
        private const val KEY_SPLIT_ORDER = "split_order_json"
        private const val ASSIGNMENT_PREFIX = "assignment::"
        private const val ALL_SPLIT_ID = "__all_sessions__"

        internal fun orderSplitIds(
            workspaces: List<Workspace>,
            latestActivityByWorkspace: Map<String, Long>,
            manualOrder: List<String>?,
        ): List<String?> {
            val workspacesById = workspaces.associateBy { it.id }
            val automaticWorkspaceIds = workspaces
                .sortedWith(
                    compareByDescending<Workspace> { latestActivityByWorkspace[it.id] ?: 0L }
                        .thenBy { it.name.lowercase() },
                )
                .map { it.id }
            val automatic = listOf(ALL_SPLIT_ID) + automaticWorkspaceIds
            if (manualOrder == null) return automatic.map { it.takeUnless { id -> id == ALL_SPLIT_ID } }

            // v0.1.21 stored workspace IDs only. Prepending All migrates that order while
            // preserving the new default that All starts first.
            val migrated = if (ALL_SPLIT_ID in manualOrder) {
                manualOrder
            } else {
                listOf(ALL_SPLIT_ID) + manualOrder
            }
            val valid = migrated
                .filter { it == ALL_SPLIT_ID || it in workspacesById }
                .distinct()
            val complete = valid + automatic.filterNot { it in valid }
            return complete.map { it.takeUnless { id -> id == ALL_SPLIT_ID } }
        }

        internal fun orderWorkspaces(
            workspaces: List<Workspace>,
            sessionCounts: Map<String, Int>,
            manualOrder: List<String>?,
        ): List<Workspace> {
            val automatic = compareByDescending<Workspace> { sessionCounts[it.id] ?: 0 }
                .thenBy { it.name.lowercase() }
            if (manualOrder == null) return workspaces.sortedWith(automatic)

            val byId = workspaces.associateBy { it.id }
            val manuallyOrdered = manualOrder.mapNotNull(byId::get)
            val included = manuallyOrdered.mapTo(mutableSetOf()) { it.id }
            return manuallyOrdered + workspaces.filterNot { it.id in included }.sortedWith(automatic)
        }

        fun projectRoot(rawPath: String): String? {
            val path = rawPath.trim().trimEnd('/')
            if (!path.startsWith('/')) return null
            val marker = "/projects/"
            val markerAt = path.indexOf(marker)
            if (markerAt >= 0) {
                val repoStart = markerAt + marker.length
                if (repoStart < path.length) {
                    val repoEnd = path.indexOf('/', repoStart).takeIf { it >= 0 } ?: path.length
                    return path.substring(0, repoEnd)
                }
            }
            return path
        }
    }
}
