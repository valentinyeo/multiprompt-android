package dev.multiprompt.companion.account

import dev.multiprompt.companion.data.SessionReadStore
import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession

object AccountHostMap {
    val labels = listOf("Hetzner", "Contabo UK", "Inne CRO")

    fun resolve(hosts: List<HostProfile>, overrides: Map<Int, String>): Map<Int, HostProfile> = buildMap {
        for (slot in 0..2) {
            val explicit = overrides[slot]
            val host = if (explicit != null) hosts.firstOrNull { it.id == explicit && (slot != 0 || it.username == "valentin") } else {
                val candidates = hosts.filter {
                    val label = "${it.label} ${it.hostname}".lowercase(java.util.Locale.ROOT)
                    when (slot) {
                        0 -> it.username == "valentin" && ("hetzner" in label || it.hostname in listOf("vps", "46.224.218.39"))
                        1 -> "contabo" in label
                        else -> "inne-cro" in label || "inne cro" in label
                    }
                }
                candidates.singleOrNull()
            }
            if (host != null && values.none { it.id == host.id }) put(slot, host)
        }
    }

    fun tabFor(session: TmuxSession, layout: AccountLayout, mapping: Map<Int, HostProfile>): AccountTab? {
        val slot = mapping.entries.firstOrNull { it.value.id == session.hostId }?.key ?: return null
        return layout.tabs.firstOrNull { it.host == slot && it.session.equals(session.name, ignoreCase = true) }
    }

    fun sessionFor(tab: AccountTab, inventory: List<TmuxSession>, mapping: Map<Int, HostProfile>, layout: AccountLayout?): TmuxSession? {
        val host = mapping[tab.host] ?: return null
        val live = inventory.firstOrNull { it.hostId == host.id && it.name.equals(tab.session, ignoreCase = true) }
        return (live ?: TmuxSession(host.id, tab.session, 1, 0, 0)).copy(title = tab.name, windowName = tab.name, accountName = tab.name, workspaceName = tab.workspace, workspaceColor = layout?.workspaceColor(tab.workspace) ?: 0)
    }

    fun withAccountSessions(inventory: List<TmuxSession>, layout: AccountLayout?, mapping: Map<Int, HostProfile>): List<TmuxSession> {
        if (layout == null) return inventory
        val missing = layout.tabs.mapNotNull { tab ->
            val host = mapping[tab.host] ?: return@mapNotNull null
            if (inventory.any { it.hostId == host.id && it.name.equals(tab.session, ignoreCase = true) }) null
            else sessionFor(tab, inventory, mapping, layout)
        }
        return inventory.map { session ->
            val tab = tabFor(session, layout, mapping)
            session.copy(accountName = tab?.name ?: session.accountName, workspaceName = tab?.workspace.orEmpty(), workspaceColor = layout.workspaceColor(tab?.workspace.orEmpty()))
        } + missing
    }

    fun workspaceAssignments(inventory: List<TmuxSession>, layout: AccountLayout?, mapping: Map<Int, HostProfile>): Map<String, String> =
        withAccountSessions(inventory, layout, mapping).mapNotNull { session ->
            layout?.let { tabFor(session, it, mapping) }?.let { SessionReadStore.key(session.hostId, session.name) to it.workspace }
        }.toMap()
}
