package dev.multiprompt.companion.sync

import org.json.JSONArray
import org.json.JSONObject

object SyncSchema {

    init {
        require(SyncProtocol.VERSION == 1) { "this schema maps envelope v1" }
    }

    private val HOST_FIELDS = listOf(
        "id", "label", "hostname", "port", "username",
        "keySecretId", "passphraseSecretId", "hostKeyType", "hostKeyFingerprint",
    )
    private val WORKSPACE_FIELDS = listOf("id", "name", "hostId", "remotePath")
    private val SESSION_STATE_KEYS = listOf("lastReadAt", "archivedAt", "resumeAt")

    data class SyncHost(
        val id: String,
        val label: String,
        val hostname: String,
        val port: Int,
        val username: String,
        val keySecretId: String,
        val passphraseSecretId: String? = null,
        val hostKeyType: String? = null,
        val hostKeyFingerprint: String? = null,
    )

    data class SyncWorkspace(
        val id: String,
        val name: String,
        val hostId: String,
        val remotePath: String,
    )

    data class SyncSessionState(
        val lastReadAt: Long,
        val archivedAt: Long? = null,
        val resumeAt: Long? = null,
    )

    data class EntityUpsert(
        val recordId: String,
        val entityId: String,
        val bodyJson: String,
    )


    fun hostsBody(hosts: List<SyncHost>): String = buildString {
        append("{\"hosts\":[")
        hosts.sortedWith(compareBy({ it.label.lowercase() }, { it.id })).forEachIndexed { i, host ->
            if (i > 0) append(",")
            append("{\"id\":").append(jsonString(host.id))
            append(",\"label\":").append(jsonString(host.label))
            append(",\"hostname\":").append(jsonString(host.hostname))
            append(",\"port\":").append(host.port)
            append(",\"username\":").append(jsonString(host.username))
            append(",\"keySecretId\":").append(jsonString(host.keySecretId))
            append(",\"passphraseSecretId\":").append(host.passphraseSecretId?.let(::jsonString) ?: "null")
            append(",\"hostKeyType\":").append(jsonString(host.hostKeyType ?: ""))
            append(",\"hostKeyFingerprint\":").append(jsonString(host.hostKeyFingerprint ?: ""))
            append("}")
        }
        append("]}")
    }

    fun parseHosts(body: String): List<SyncHost> {
        val array = JSONObject(body).getJSONArray("hosts")
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    SyncHost(
                        id = item.getString("id"),
                        label = item.getString("label"),
                        hostname = item.getString("hostname"),
                        port = item.optInt("port", 22),
                        username = item.getString("username"),
                        keySecretId = item.getString("keySecretId"),
                        passphraseSecretId = item.optString("passphraseSecretId").ifBlank { null },
                        hostKeyType = item.optString("hostKeyType").ifBlank { null },
                        hostKeyFingerprint = item.optString("hostKeyFingerprint").ifBlank { null },
                    ),
                )
            }
        }
    }


    fun workspacesBody(workspaces: List<SyncWorkspace>): String = buildString {
        append("{\"workspaces\":[")
        workspaces.sortedBy { it.name.lowercase() }.forEachIndexed { i, workspace ->
            if (i > 0) append(",")
            append("{\"id\":").append(jsonString(workspace.id))
            append(",\"name\":").append(jsonString(workspace.name))
            append(",\"hostId\":").append(jsonString(workspace.hostId))
            append(",\"remotePath\":").append(jsonString(workspace.remotePath))
            append("}")
        }
        append("]}")
    }

    fun parseWorkspaces(body: String): List<SyncWorkspace> {
        val array = JSONObject(body).getJSONArray("workspaces")
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    SyncWorkspace(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        hostId = item.getString("hostId"),
                        remotePath = item.getString("remotePath"),
                    ),
                )
            }
        }
    }


    fun sessionStateBody(state: SyncSessionState): String = buildString {
        append("{\"lastReadAt\":").append(state.lastReadAt)
        append(",\"archivedAt\":").append(state.archivedAt ?: "null")
        append(",\"resumeAt\":").append(state.resumeAt ?: "null")
        append("}")
    }

    fun parseSessionState(body: String): SyncSessionState {
        val item = JSONObject(body)
        val archived = if (item.isNull("archivedAt")) null else item.getLong("archivedAt")
        val resumeAt = if (item.isNull("resumeAt")) null else item.getLong("resumeAt")
        return SyncSessionState(
            lastReadAt = item.getLong("lastReadAt"),
            archivedAt = archived,
            resumeAt = resumeAt?.takeIf { it > 0 },
        )
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun sessionEntityId(hostUuid: String, tmuxSessionName: String): String {
        require(hostUuid.isNotBlank()) { "host uuid must not be blank" }
        require(tmuxSessionName.isNotBlank()) { "tmux session name must not be blank" }
        val encodedName = java.net.URLEncoder.encode(tmuxSessionName, "UTF-8")
            .replace("+", "%20")
        return "$hostUuid--$encodedName"
    }

    fun sessionIdFrom(entityId: String): Pair<String, String>? {
        val separator = entityId.indexOf("--")
        if (separator <= 0) return null
        val hostUuid = entityId.substring(0, separator)
        val name = java.net.URLDecoder.decode(entityId.substring(separator + 2), "UTF-8")
        return hostUuid to name
    }
}
