package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession

const val FREQUENT_SPLIT_ID = "\u0000frequent"

const val VISIT_WINDOW = 6 * 60 * 60 * 1000L
const val MIN_VISITS = 3
const val DWELL = 10_000L
const val MAX_VISITS = 8

fun mergeVisits(vararg values: List<Long>?): List<Long>? =
    if (values.all { it == null }) null else values.filterNotNull().flatten().distinct().sorted().takeLast(MAX_VISITS)

fun frequentSessions(
    sessions: List<TmuxSession>,
    layout: AccountLayout?,
    mapping: Map<Int, HostProfile>,
    now: Long,
    localFront: TmuxSession? = null,
): List<TmuxSession> {
    val newest = layout?.tabs?.mapNotNull { it.visits?.lastOrNull() }?.maxOrNull()
    return sessions.mapNotNull { session ->
        val visits = layout?.let { AccountHostMap.tabFor(session, it, mapping) }?.visits.orEmpty()
        val front = localFront?.let { it.hostId == session.hostId && it.name.equals(session.name, ignoreCase = true) } == true
        val latest = visits.lastOrNull()
        if (front || visits.count { it >= now - VISIT_WINDOW } >= MIN_VISITS ||
            latest != null && latest == newest && latest >= now - VISIT_WINDOW) {
            session to if (front) now else latest!!
        } else null
    }.sortedByDescending { it.second }.map { it.first }
}

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
