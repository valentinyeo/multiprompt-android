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
        editor.putString(KEY_WORKSPACES, encode(remaining)).apply()
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
        private const val ASSIGNMENT_PREFIX = "assignment::"

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
