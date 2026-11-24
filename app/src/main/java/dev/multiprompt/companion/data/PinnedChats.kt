package dev.multiprompt.companion.data

import android.content.Context
import dev.multiprompt.companion.model.TmuxSession
import org.json.JSONArray

object PinnedChats {
    const val PIN_LABEL = "Pin to top"
    const val UNPIN_LABEL = "Unpin"

    fun key(hostId: String, sessionName: String): String = SessionReadStore.key(hostId, sessionName)

    fun key(session: TmuxSession): String = key(session.hostId, session.name)

    fun isPinned(pinnedKeys: List<String>, session: TmuxSession): Boolean = key(session) in pinnedKeys

    fun pin(pinnedKeys: List<String>, key: String): List<String> {
        if (key.isBlank() || key in pinnedKeys) return pinnedKeys
        return pinnedKeys + key
    }

    fun unpin(pinnedKeys: List<String>, key: String): List<String> =
        pinnedKeys.filterNot { it == key }

    fun removeHost(pinnedKeys: List<String>, hostId: String): List<String> {
        val prefix = SessionReadStore.key(hostId, "")
        return pinnedKeys.filterNot { it.startsWith(prefix) }
    }

    fun prune(pinnedKeys: List<String>, hostId: String, listedNames: Collection<String>): List<String> {
        if (pinnedKeys.isEmpty()) return pinnedKeys
        val prefix = SessionReadStore.key(hostId, "")
        val listed = listedNames.mapTo(mutableSetOf()) { key(hostId, it) }
        return pinnedKeys.filterNot { it.startsWith(prefix) && it !in listed }
    }

    fun <T> order(items: List<T>, pinnedKeys: List<String>, keyOf: (T) -> String, atEnd: Boolean = false): List<T> {
        if (pinnedKeys.isEmpty() || items.size <= 1) return items
        val position = pinnedKeys.withIndex().associate { (index, key) -> key to index }
        val pinned = items.filter { keyOf(it) in position }.sortedBy { position.getValue(keyOf(it)) }
        if (pinned.isEmpty()) return items
        val unpinned = items.filter { keyOf(it) !in position }
        return if (atEnd) unpinned + pinned else pinned + unpinned
    }

    fun order(sessions: List<TmuxSession>, pinnedKeys: List<String>, atEnd: Boolean = false): List<TmuxSession> =
        order(sessions, pinnedKeys, ::key, atEnd)

    fun encode(pinnedKeys: List<String>): String {
        val array = JSONArray()
        pinnedKeys.filter(String::isNotBlank).distinct().forEach(array::put)
        return array.toString()
    }

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.opt(index) as? String ?: continue
                    if (value.isNotBlank() && value !in this) add(value)
                }
            }
        }.getOrDefault(emptyList())
    }
}

class PinnedChatStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<String> = PinnedChats.decode(preferences.getString(KEY, null))

    fun pin(session: TmuxSession): List<String> = save(PinnedChats.pin(load(), PinnedChats.key(session)))

    fun unpin(session: TmuxSession): List<String> = save(PinnedChats.unpin(load(), PinnedChats.key(session)))

    fun removeHost(hostId: String): List<String> = save(PinnedChats.removeHost(load(), hostId))

    fun prune(listedNames: Map<String, Collection<String>>): List<String> {
        val current = load()
        val pruned = listedNames.entries.fold(current) { keys, (hostId, names) ->
            PinnedChats.prune(keys, hostId, names)
        }
        return if (pruned == current) current else save(pruned)
    }

    private fun save(keys: List<String>): List<String> {
        preferences.edit().putString(KEY, PinnedChats.encode(keys)).apply()
        return keys
    }

    private companion object {
        const val PREFS_NAME = "pinned_chats"
        const val KEY = "pinned_keys_json"
    }
}
