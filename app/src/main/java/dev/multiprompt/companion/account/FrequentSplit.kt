package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession

const val FREQUENT_SPLIT_ID = "\u0000frequent"

fun frequentSessions(
    sessions: List<TmuxSession>,
    layout: AccountLayout?,
    mapping: Map<Int, HostProfile>,
    now: Long,
): List<TmuxSession> = sessions.mapNotNull { session ->
    val used = layout?.let { AccountHostMap.tabFor(session, it, mapping) }?.used ?: return@mapNotNull null
    if (used != 0L && (used > now || now - used <= 48 * 60 * 60 * 1000L)) session to used else null
}.sortedByDescending { it.second }.map { it.first }

class UsedStampThrottle(initial: AccountLayout? = null) {
    private val known = mutableMapOf<String, Long>()

    init { initial?.let(::observe) }

    fun observe(layout: AccountLayout) {
        layout.tabs.forEach { tab ->
            tab.used?.let { known[tab.identity] = maxOf(known[tab.identity] ?: 0, it) }
        }
    }

    fun shouldPush(pending: PendingLayout): Boolean {
        val withoutStamps = pending.edited.copy(tabs = pending.edited.tabs.map { tab ->
            tab.copy(used = pending.base.tabs.firstOrNull { it.identity == tab.identity }?.used)
        })
        return !pending.base.sameContent(withoutStamps) || pending.edited.tabs.any { tab ->
            val used = tab.used ?: return@any false
            val previous = known[tab.identity]
            used != 0L && (previous == null || used > previous && used - previous > 10 * 60 * 1000L)
        }
    }
}
