package dev.multiprompt.companion.ui

data class SessionGroup<T>(val name: String?, val items: List<T>, val pinned: Boolean = false)

object SessionGroups {
    const val RECENTS = "Recents"
    const val PINNED = "Pinned"
    const val PINNED_KEY = "pinned-section"
    private const val RECENTS_KEY = "recents"

    fun <T> heading(group: SessionGroup<T>, all: List<SessionGroup<T>>): String? =
        group.name ?: RECENTS.takeIf { all.any { it.name != null } }

    fun <T> key(group: SessionGroup<T>): String = when {
        group.pinned -> PINNED_KEY
        group.name != null -> "group:${group.name}"
        else -> RECENTS_KEY
    }

    fun <T> renameTarget(group: SessionGroup<T>): String? = group.name?.takeUnless { group.pinned }

    fun <T> withPinned(groups: List<SessionGroup<T>>, pinned: List<T>, atEnd: Boolean): List<SessionGroup<T>> {
        if (pinned.isEmpty() || groups.none { it.name != null }) return groups
        val pinnedItems = pinned.toSet()
        val rest = groups.mapNotNull { group ->
            val items = group.items.filterNot { it in pinnedItems }
            items.takeIf { it.isNotEmpty() }?.let { SessionGroup(group.name, it) }
        }
        val pinnedGroup = SessionGroup(PINNED, pinned, pinned = true)
        return if (atEnd) rest + pinnedGroup else listOf(pinnedGroup) + rest
    }

    fun <T> split(items: List<T>, order: List<String>? = null, groupOf: (T) -> String?): List<SessionGroup<T>> {
        val loose = items.filter { groupOf(it).isNullOrEmpty() }
        val position = order.orEmpty().withIndex().associate { (index, name) -> name to index }
        val named = items.mapNotNull { groupOf(it)?.takeIf(String::isNotEmpty) }.distinct()
            .sortedBy { position[it] ?: Int.MAX_VALUE }
            .map { name -> SessionGroup(name, items.filter { groupOf(it) == name }) }
        return listOfNotNull(SessionGroup<T>(null, loose).takeIf { loose.isNotEmpty() }) + named
    }
}
