package dev.multiprompt.companion.account

import dev.multiprompt.companion.model.HostProfile
import dev.multiprompt.companion.model.TmuxSession

const val FREQUENT_SPLIT_ID = "\u0000frequent"

const val INPUT_WINDOW = 24 * 60 * 60 * 1000L
const val LOOK_WINDOW = 6 * 60 * 60 * 1000L
const val MIN_LOOKS = 3
const val DWELL = 10_000L
const val MAX_VISITS = 8

fun mergeVisits(vararg values: List<Long>?): List<Long>? =
    if (values.all { it == null }) null else values.filterNotNull().flatten().distinct().sorted().takeLast(MAX_VISITS)

fun frequentSessions(
    sessions: List<TmuxSession>,
    layout: AccountLayout?,
    mapping: Map<Int, HostProfile>,
    now: Long,
): List<TmuxSession> = sessions.mapNotNull { session ->
    val tab = layout?.let { AccountHostMap.tabFor(session, it, mapping) } ?: return@mapNotNull null
    val key = if (tab.input != null && tab.input >= now - INPUT_WINDOW) tab.input
        else if (tab.visits.orEmpty().count { it >= now - LOOK_WINDOW } >= MIN_LOOKS) tab.visits?.lastOrNull()
        else null
    key?.let { session to it }
}.sortedByDescending { it.second }.map { it.first }

class UsedStampThrottle(initial: AccountLayout? = null) {
    private val known = mutableMapOf<String, Long>()
    private val knownInput = mutableMapOf<String, Long>()

    init { initial?.let(::observe) }

    fun observe(layout: AccountLayout) {
        layout.tabs.forEach { tab ->
            tab.used?.let { known[tab.identity] = maxOf(known[tab.identity] ?: 0, it) }
            tab.input?.let { knownInput[tab.identity] = maxOf(knownInput[tab.identity] ?: 0, it) }
        }
    }

    fun shouldPush(pending: PendingLayout): Boolean {
        val withoutStamps = pending.edited.copy(tabs = pending.edited.tabs.map { tab ->
            tab.copy(used = pending.base.tabs.firstOrNull { it.identity == tab.identity }?.used,
                input = pending.base.tabs.firstOrNull { it.identity == tab.identity }?.input)
        })
        return !pending.base.sameContent(withoutStamps) || pending.edited.tabs.any { tab ->
            val input = tab.input ?: return@any false
            val previous = knownInput[tab.identity]
            previous == null || input > previous && input - previous > 60_000L
        } || pending.edited.tabs.any { tab ->
            val used = tab.used ?: return@any false
            val previous = known[tab.identity]
            used != 0L && (previous == null || used > previous && used - previous > 10 * 60 * 1000L)
        }
    }
}
