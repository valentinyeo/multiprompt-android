package dev.multiprompt.companion.account

import org.json.JSONObject

data class ArchivedWorkspace(
    val name: String,
    val color: Int? = null,
    val tabs: List<AccountTab> = emptyList(),
) {
    fun json(): JSONObject = AccountLayout(
        workspaces = listOf(name),
        tabs = tabs,
        workspaceColors = color?.let { mapOf(name to it) } ?: emptyMap(),
    ).json()

    companion object {
        fun parse(json: JSONObject): ArchivedWorkspace {
            val layout = AccountLayout.parse(json)
            require(layout.workspaces.size == 1) { "Archived workspace must hold one workspace" }
            val name = layout.workspaces.single()
            return ArchivedWorkspace(name, layout.workspaceColors[name], layout.tabs)
        }
    }
}

data class WorkspaceArchiveResult(val layout: AccountLayout, val archived: List<ArchivedWorkspace>)

object WorkspaceArchive {
    const val MAIN_NAME = "Main"

    fun isMain(name: String): Boolean = name.equals(MAIN_NAME, ignoreCase = true)

    fun isHiddenMain(id: String?, selectedId: String?, assignedWorkspaceIds: Collection<String>): Boolean =
        id != null && isMain(id) && id != selectedId && assignedWorkspaceIds.none { it == id }

    fun archive(layout: AccountLayout, archived: List<ArchivedWorkspace>, name: String): WorkspaceArchiveResult {
        require(name in layout.workspaces) { "That workspace is no longer there" }
        require(!isMain(name)) { "Main cannot be archived" }
        val slice = ArchivedWorkspace(name, layout.workspaceColors[name], layout.tabs.filter { it.workspace == name })
        val next = layout.copy(
            workspaces = layout.workspaces - name,
            workspaceColors = layout.workspaceColors - name,
            tabs = layout.tabs.filterNot { it.workspace == name },
        ).validate()
        return WorkspaceArchiveResult(next, archived.filterNot { it.name == name } + slice)
    }

    fun restore(layout: AccountLayout, archived: List<ArchivedWorkspace>, name: String): WorkspaceArchiveResult {
        val slice = archived.firstOrNull { it.name == name } ?: throw IllegalArgumentException("That workspace is not archived")
        require(name !in layout.workspaces) { "A workspace named $name already exists" }
        val taken = layout.tabs.map { it.identity }.toSet()
        val next = layout.copy(
            workspaces = layout.workspaces + name,
            workspaceColors = if (slice.color == null) layout.workspaceColors else layout.workspaceColors + (name to slice.color),
            tabs = layout.tabs + slice.tabs.filterNot { it.identity in taken },
        ).validate()
        return WorkspaceArchiveResult(next, archived.filterNot { it.name == name })
    }

    fun delete(archived: List<ArchivedWorkspace>, name: String): List<ArchivedWorkspace> {
        require(archived.any { it.name == name }) { "Only an archived workspace can be deleted" }
        return archived.filterNot { it.name == name }
    }
}
