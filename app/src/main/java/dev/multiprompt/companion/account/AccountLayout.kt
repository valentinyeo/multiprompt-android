package dev.multiprompt.companion.account

import org.json.JSONArray
import org.json.JSONObject

data class AccountTab(
    val name: String,
    val session: String,
    val host: Int,
    val workspace: String,
    val manual: Boolean = false,
    val favorite: Boolean? = null,
    val used: Long? = null,
    val visits: List<Long>? = null,
    val input: Long? = null,
    val muted: Boolean? = null,
) {
    val identity: String get() = "$host:${session.lowercase(java.util.Locale.ROOT)}"
    fun json(): JSONObject = JSONObject().put("kind", "tmux").put("name", name)
        .put("tmux_session", session).put("tmux_host", host).put("workspace", workspace).put("manual", manual)
        .apply { favorite?.let { put("favorite", it) }; muted?.let { put("muted", it) }; used?.let { put("used", it) }; input?.let { put("input", it) }; visits?.let { put("visits", JSONArray(it)) } }
}

data class AccountLayout(
    val revision: Long = 0,
    val workspaces: List<String> = emptyList(),
    val tabs: List<AccountTab> = emptyList(),
    val settings: Map<String, Boolean>? = null,
    val hosts: List<AccountHost>? = null,
    val workspaceColors: Map<String, Int> = emptyMap(),
) {
    fun validate(): AccountLayout {
        require(workspaceColors.all { (name, color) -> name in workspaces && color in 0..6 }) { "Invalid workspace color" }
        require(hosts == null || AccountHosts.parse(JSONArray(hosts.map { it.json() })) == hosts) { "Invalid layout hosts" }
        require(settings == null || settings.keys.all { it in setOf("frequent", "favorites", "all", "frequent_first") }) { "Invalid layout settings" }
        require(revision >= 0 && workspaces.size <= 9 && tabs.size <= 256) { "Invalid layout capacity or revision" }
        require(workspaces.distinct().size == workspaces.size && workspaces.all {
            validText(it, 32) && it.toByteArray(Charsets.UTF_8).size <= 31
        }) { "Workspace names must be unique and at most 31 UTF-8 bytes" }
        require(tabs.map { it.identity }.distinct().size == tabs.size && tabs.all {
            validText(it.name, 64) && it.host in 0..2 && Regex("[A-Za-z0-9_-]{1,63}").matches(it.session) && it.workspace in workspaces &&
                (it.used == null || it.used in 0..9_007_199_254_740_991L) &&
                (it.input == null || it.input in 0..9_007_199_254_740_991L) &&
                (it.visits == null || it.visits.size <= MAX_VISITS && it.visits.all { v -> v in 0..9_007_199_254_740_991L } &&
                    it.visits.zipWithNext().all { (a, b) -> a < b })
        }) { "Invalid or duplicate account tab" }
        return this
    }

    fun json(includeWorkspaceColors: Boolean = true): JSONObject = JSONObject().put("schema", 1).put("revision", revision)
        .put("workspaces", JSONArray(workspaces.map { JSONObject().put("name", it).apply { if (includeWorkspaceColors) workspaceColors[it]?.let { color -> put("color", color) } } }))
        .put("tabs", JSONArray(tabs.map { it.json() }))
        .apply { settings?.let { put("settings", JSONObject(it)) }; hosts?.let { put("hosts", JSONArray(it.map { host -> host.json() })) } }

    fun sameContent(other: AccountLayout): Boolean = workspaces == other.workspaces && workspaceColors == other.workspaceColors && tabs == other.tabs && settings == other.settings && hosts.orEmpty() == other.hosts.orEmpty()

    fun workspaceColor(name: String): Int = workspaceColors[name] ?: workspaces.indexOf(name).coerceAtLeast(0) % 7

    fun renameTab(identity: String, name: String): AccountLayout = copy(tabs = tabs.map {
        if (it.identity == identity) it.copy(name = name, manual = true) else it
    }).validate()

    fun addTab(tab: AccountTab): AccountLayout = copy(tabs = tabs.filterNot { it.identity == tab.identity } + tab).validate()
    fun closeTab(identity: String): AccountLayout = copy(tabs = tabs.filterNot { it.identity == identity })
    fun moveTab(identity: String, workspace: String): AccountLayout = copy(tabs = tabs.map {
        if (it.identity == identity) it.copy(workspace = workspace) else it
    }).validate()
    fun setFavorite(identity: String, value: Boolean): AccountLayout = copy(tabs = tabs.map {
        if (it.identity == identity) it.copy(favorite = value) else it
    }).validate()
    fun stampUsed(identity: String, now: Long): AccountLayout = copy(tabs = tabs.map {
        if (it.identity == identity) it.copy(used = maxOf(it.used ?: 0, now)) else it
    }).validate()
    fun setSetting(key: String, value: Boolean): AccountLayout = copy(settings = settings.orEmpty() + (key to value)).validate()
    fun stampInput(identity: String, now: Long): AccountLayout = copy(tabs = tabs.map {
        if (it.identity == identity) it.copy(input = maxOf(it.input ?: 0, now), used = maxOf(it.used ?: 0, now)) else it
    }).validate()
    fun recordVisit(identity: String, startedAt: Long): AccountLayout {
        require(startedAt in 0..9_007_199_254_740_991L)
        return copy(tabs = tabs.map {
            if (it.identity == identity) it.copy(visits = mergeVisits(it.visits, listOf(startedAt))) else it
        }).validate()
    }
    fun renameWorkspace(old: String, name: String): AccountLayout = copy(
        workspaces = workspaces.map { if (it == old) name else it },
        workspaceColors = workspaceColors.mapKeys { (key, _) -> if (key == old) name else key },
        tabs = tabs.map { if (it.workspace == old) it.copy(workspace = name) else it },
    ).validate()

    companion object {
        private fun validText(text: String, limit: Int): Boolean = text.isNotEmpty() && text.length <= limit &&
            text.none { it.code < 32 || it.code == 127 } &&
            Charsets.UTF_8.newEncoder().canEncode(text)

        fun parse(json: JSONObject): AccountLayout {
            val required = setOf("schema", "revision", "workspaces", "tabs")
            val keys = json.keys().asSequence().toSet()
            require(keys.containsAll(required) && (keys - required).all { it in setOf("settings", "hosts") } && json.get("schema") == 1)
            val settings = if (json.has("settings")) {
                val value = json.get("settings")
                require(value is JSONObject)
                value.keys().asSequence().associateWith { key ->
                    val flag = value.get(key)
                    require(flag is Boolean)
                    flag
                }
            } else null
            val revision = json.get("revision")
            require(revision is Number && revision.toDouble() == revision.toLong().toDouble())
            val ws = json.getJSONArray("workspaces")
            val tabs = json.getJSONArray("tabs")
            return AccountLayout(revision.toLong(), (0 until ws.length()).map {
                val w = ws.getJSONObject(it)
                require(w.keys().asSequence().toSet() in setOf(setOf("name"), setOf("name", "color")) && w.get("name") is String)
                if (w.has("color")) {
                    val color = w.get("color")
                    require(color is Number && color.toDouble() in 0.0..6.0 && color.toDouble() == color.toInt().toDouble())
                }
                w.getString("name")
            }, (0 until tabs.length()).map {
                val t = tabs.getJSONObject(it)
                val required = setOf("kind", "name", "tmux_session", "tmux_host", "workspace")
                val keys = t.keys().asSequence().toSet()
                require(keys.containsAll(required) && (keys - required).all { it in setOf("manual", "favorite", "muted", "used", "visits", "input") } && t.get("kind") == "tmux")
                require(listOf("name", "tmux_session", "workspace").all { key -> t.get(key) is String })
                val host = t.get("tmux_host")
                require(host is Number && host.toDouble() == host.toInt().toDouble())
                require(!t.has("manual") || t.get("manual") is Boolean)
                require(!t.has("favorite") || t.get("favorite") is Boolean)
                require(!t.has("muted") || t.get("muted") is Boolean)
                val used = if (t.has("used")) t.get("used") else null
                require(!t.has("used") || used is Number && used.toLong() in 0..9_007_199_254_740_991L &&
                    used.toString().toBigDecimalOrNull()?.compareTo(used.toLong().toBigDecimal()) == 0)
                val input = if (t.has("input")) t.get("input") else null
                require(!t.has("input") || input is Number && input.toLong() in 0..9_007_199_254_740_991L &&
                    input.toString().toBigDecimalOrNull()?.compareTo(input.toLong().toBigDecimal()) == 0)
                val visits = if (t.has("visits")) {
                    val values = t.get("visits")
                    require(values is JSONArray && values.length() <= MAX_VISITS)
                    (0 until values.length()).map { index ->
                        val value = values.get(index)
                        require(value is Number && value.toLong() in 0..9_007_199_254_740_991L &&
                            value.toString().toBigDecimalOrNull()?.compareTo(value.toLong().toBigDecimal()) == 0)
                        value.toLong()
                    }
                } else null
                AccountTab(t.getString("name"), t.getString("tmux_session"), host.toInt(), t.getString("workspace"),
                    t.optBoolean("manual", false), if (t.has("favorite")) t.getBoolean("favorite") else null, (used as? Number)?.toLong(), visits, (input as? Number)?.toLong(),
                    if (t.has("muted")) t.getBoolean("muted") else null)
            }, settings, if (json.has("hosts")) AccountHosts.parse(json.opt("hosts")) else null,
                (0 until ws.length()).map { ws.getJSONObject(it) }.filter { it.has("color") }
                    .associate { it.getString("name") to it.getInt("color") }).validate()
        }

        fun response(json: JSONObject): AccountLayout {
            val layout = parse(json.getJSONObject("layout"))
            require(json.getLong("revision") == layout.revision) { "Layout revision mismatch" }
            return layout
        }

        fun rebase(base: AccountLayout, edited: AccountLayout, remote: AccountLayout): AccountLayout {
            val old = base.tabs.associateBy { it.identity }
            val local = edited.tabs.associateBy { it.identity }
            val workspaces = remote.workspaces.filterNot { it in base.workspaces && it !in edited.workspaces }.toMutableList()
            edited.workspaces.filter { it !in base.workspaces && it !in workspaces }.forEach(workspaces::add)
            val tabs = remote.tabs.mapNotNull { r ->
                val b = old[r.identity] ?: return@mapNotNull r.copy(
                    used = listOfNotNull(r.used, local[r.identity]?.used).maxOrNull(),
                    input = listOfNotNull(r.input, local[r.identity]?.input).maxOrNull(),
                    visits = mergeVisits(r.visits, local[r.identity]?.visits),
                )
                val e = local[r.identity] ?: return@mapNotNull null
                r.copy(
                    name = if (e.name != b.name || e.manual != b.manual) e.name else r.name,
                    manual = if (e.name != b.name || e.manual != b.manual) e.manual else r.manual,
                    workspace = if (e.workspace != b.workspace) e.workspace else r.workspace,
                    favorite = if (e.favorite != b.favorite) e.favorite else r.favorite,
                    muted = if (e.muted != b.muted) e.muted else r.muted,
                    used = listOfNotNull(b.used, e.used, r.used).maxOrNull(),
                    input = listOfNotNull(b.input, e.input, r.input).maxOrNull(),
                    visits = mergeVisits(b.visits, e.visits, r.visits),
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
            val settings = if (edited.settings == base.settings) remote.settings else {
                val merged = remote.settings.orEmpty().toMutableMap()
                (base.settings.orEmpty().keys + edited.settings.orEmpty().keys).forEach { key ->
                    if (edited.settings?.get(key) != base.settings?.get(key)) {
                        edited.settings?.get(key)?.let { merged[key] = it } ?: merged.remove(key)
                    }
                }
                merged.takeIf { it.isNotEmpty() || edited.settings != null || remote.settings != null }
            }
            val colors = remote.workspaceColors.toMutableMap()
            (base.workspaceColors.keys + edited.workspaceColors.keys).forEach { name ->
                if (edited.workspaceColors[name] != base.workspaceColors[name]) {
                    edited.workspaceColors[name]?.let { colors[name] = it } ?: colors.remove(name)
                }
            }
            return remote.copy(workspaces = orderedWorkspaces, tabs = orderedTabs, settings = settings,
                workspaceColors = colors.filterKeys { it in orderedWorkspaces }).validate()
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
