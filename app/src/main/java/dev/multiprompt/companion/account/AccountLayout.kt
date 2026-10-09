package dev.multiprompt.companion.account

import org.json.JSONArray
import org.json.JSONObject

data class AccountTab(
    val name: String,
    val session: String,
    val host: Int,
    val workspace: String,
    val manual: Boolean = false,
) {
    val identity: String get() = "$host:${session.lowercase(java.util.Locale.ROOT)}"
    fun json(): JSONObject = JSONObject().put("kind", "tmux").put("name", name)
        .put("tmux_session", session).put("tmux_host", host).put("workspace", workspace).put("manual", manual)
}

data class AccountLayout(
    val revision: Long = 0,
    val workspaces: List<String> = emptyList(),
    val tabs: List<AccountTab> = emptyList(),
) {
    fun validate(): AccountLayout {
        require(revision >= 0 && workspaces.size <= 9 && tabs.size <= 256) { "Invalid layout capacity or revision" }
        require(workspaces.distinct().size == workspaces.size && workspaces.all {
            validText(it, 32) && it.toByteArray(Charsets.UTF_8).size <= 31
        }) { "Workspace names must be unique and at most 31 UTF-8 bytes" }
        require(tabs.map { it.identity }.distinct().size == tabs.size && tabs.all {
            validText(it.name, 64) && it.host in 0..2 && Regex("[A-Za-z0-9_-]{1,63}").matches(it.session) && it.workspace in workspaces
        }) { "Invalid or duplicate account tab" }
        return this
    }

    fun json(): JSONObject = JSONObject().put("schema", 1).put("revision", revision)
        .put("workspaces", JSONArray(workspaces.map { JSONObject().put("name", it) }))
        .put("tabs", JSONArray(tabs.map { it.json() }))

    fun sameContent(other: AccountLayout): Boolean = workspaces == other.workspaces && tabs == other.tabs

    fun renameTab(identity: String, name: String): AccountLayout = copy(tabs = tabs.map {
        if (it.identity == identity) it.copy(name = name, manual = true) else it
    }).validate()

    fun addTab(tab: AccountTab): AccountLayout = copy(tabs = tabs.filterNot { it.identity == tab.identity } + tab).validate()
    fun closeTab(identity: String): AccountLayout = copy(tabs = tabs.filterNot { it.identity == identity })
    fun moveTab(identity: String, workspace: String): AccountLayout = copy(tabs = tabs.map {
        if (it.identity == identity) it.copy(workspace = workspace) else it
    }).validate()
    fun renameWorkspace(old: String, name: String): AccountLayout = copy(
        workspaces = workspaces.map { if (it == old) name else it },
        tabs = tabs.map { if (it.workspace == old) it.copy(workspace = name) else it },
    ).validate()

    companion object {
        private fun validText(text: String, limit: Int): Boolean = text.isNotEmpty() && text.length <= limit &&
            text.none { it.code < 32 || it.code == 127 } &&
            Charsets.UTF_8.newEncoder().canEncode(text)

        fun parse(json: JSONObject): AccountLayout {
            require(json.keys().asSequence().toSet() == setOf("schema", "revision", "workspaces", "tabs") && json.get("schema") == 1)
            val revision = json.get("revision")
            require(revision is Number && revision.toDouble() == revision.toLong().toDouble())
            val ws = json.getJSONArray("workspaces")
            val tabs = json.getJSONArray("tabs")
            return AccountLayout(revision.toLong(), (0 until ws.length()).map {
                val w = ws.getJSONObject(it)
                require(w.keys().asSequence().toSet() == setOf("name") && w.get("name") is String)
                w.getString("name")
            }, (0 until tabs.length()).map {
                val t = tabs.getJSONObject(it)
                require(t.keys().asSequence().toSet() in listOf(
                    setOf("kind", "name", "tmux_session", "tmux_host", "workspace"),
                    setOf("kind", "name", "tmux_session", "tmux_host", "workspace", "manual"),
                ) && t.get("kind") == "tmux")
                require(listOf("name", "tmux_session", "workspace").all { key -> t.get(key) is String })
                val host = t.get("tmux_host")
                require(host is Number && host.toDouble() == host.toInt().toDouble())
                require(!t.has("manual") || t.get("manual") is Boolean)
                AccountTab(t.getString("name"), t.getString("tmux_session"), host.toInt(), t.getString("workspace"), t.optBoolean("manual", false))
            }).validate()
        }

        fun response(json: JSONObject): AccountLayout {
            val layout = parse(json.getJSONObject("layout"))
            require(json.getLong("revision") == layout.revision) { "Layout revision mismatch" }
            return layout
        }

        // Replay changed fields, not a cached snapshot. Remote deletions cannot be revived by a rename.
        fun rebase(base: AccountLayout, edited: AccountLayout, remote: AccountLayout): AccountLayout {
            val old = base.tabs.associateBy { it.identity }
            val local = edited.tabs.associateBy { it.identity }
            val workspaces = remote.workspaces.filterNot { it in base.workspaces && it !in edited.workspaces }.toMutableList()
            edited.workspaces.filter { it !in base.workspaces && it !in workspaces }.forEach(workspaces::add)
            val tabs = remote.tabs.mapNotNull { r ->
                val b = old[r.identity] ?: return@mapNotNull r
                val e = local[r.identity] ?: return@mapNotNull null
                r.copy(
                    name = if (e.name != b.name || e.manual != b.manual) e.name else r.name,
                    manual = if (e.name != b.name || e.manual != b.manual) e.manual else r.manual,
                    workspace = if (e.workspace != b.workspace) e.workspace else r.workspace,
                )
            }.toMutableList()
            edited.tabs.filter { it.identity !in old && tabs.none { r -> r.identity == it.identity } }.forEach(tabs::add)
            tabs.map { it.workspace }.filterNot { it in workspaces }.distinct().forEach(workspaces::add)
            val orderedTabs = if (base.tabs.map { it.identity } != edited.tabs.map { it.identity }) {
                val byId = tabs.associateBy { it.identity }
                edited.tabs.mapNotNull { byId[it.identity] } + tabs.filterNot { it.identity in local }
            } else tabs
            val orderedWorkspaces = if (base.workspaces != edited.workspaces) {
                edited.workspaces.filter { it in workspaces } + workspaces.filterNot { it in edited.workspaces }
            } else workspaces
            return remote.copy(workspaces = orderedWorkspaces, tabs = orderedTabs).validate()
        }
    }
}

data class PendingLayout(val base: AccountLayout, val edited: AccountLayout = base) {
    val dirty: Boolean get() = !base.sameContent(edited)
    fun pull(remote: AccountLayout): PendingLayout = PendingLayout(remote, AccountLayout.rebase(base, edited, remote))
    fun acknowledge(sent: AccountLayout, remote: AccountLayout): PendingLayout = PendingLayout(remote, AccountLayout.rebase(sent, edited, remote))
    fun json(): JSONObject = JSONObject().put("base", base.json()).put("edited", edited.json())
    companion object {
        fun parse(json: JSONObject): PendingLayout = PendingLayout(AccountLayout.parse(json.getJSONObject("base")), AccountLayout.parse(json.getJSONObject("edited")))
    }
}
