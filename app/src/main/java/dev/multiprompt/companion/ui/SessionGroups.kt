package dev.multiprompt.companion.ui

data class SessionGroup<T>(val name: String?, val items: List<T>)

object SessionGroups {
    const val RECENTS = "Recents"

    fun <T> heading(group: SessionGroup<T>, all: List<SessionGroup<T>>): String? =
        group.name ?: RECENTS.takeIf { all.any { it.name != null } }

    fun <T> split(items: List<T>, order: List<String>? = null, groupOf: (T) -> String?): List<SessionGroup<T>> {
        val loose = items.filter { groupOf(it).isNullOrEmpty() }
        val position = order.orEmpty().withIndex().associate { (index, name) -> name to index }
        val named = items.mapNotNull { groupOf(it)?.takeIf(String::isNotEmpty) }.distinct()
            .sortedBy { position[it] ?: Int.MAX_VALUE }
            .map { name -> SessionGroup(name, items.filter { groupOf(it) == name }) }
        return listOfNotNull(SessionGroup<T>(null, loose).takeIf { loose.isNotEmpty() }) + named
    }
}
