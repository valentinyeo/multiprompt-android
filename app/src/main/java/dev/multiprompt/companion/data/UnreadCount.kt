package dev.multiprompt.companion.data

object UnreadCount {
    data class State(
        val count: Int = 0,
        val lastActivity: Long? = null,
        val lastCountedActivity: Long = 0,
        val wasWorking: Boolean = false,
    )

    fun step(state: State, activity: Long, waiting: Boolean, unread: Boolean): State {
        if (!unread) return read(state, activity, waiting)
        if (state.lastActivity?.let { activity < it } == true) return state
        val finished = waiting && activity > state.lastCountedActivity &&
            (state.wasWorking || state.lastActivity?.let { activity > it } == true)
        return state.copy(
            count = if (finished) (state.count.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else state.count,
            lastActivity = activity,
            lastCountedActivity = if (finished) activity else state.lastCountedActivity,
            wasWorking = !waiting,
        )
    }

    fun read(state: State, activity: Long, waiting: Boolean): State = state.copy(
        count = 0,
        lastActivity = activity,
        lastCountedActivity = maxOf(state.lastCountedActivity, activity),
        wasWorking = !waiting,
    )

    fun unread(state: State): State = state.copy(count = state.count.coerceAtLeast(1))

    fun displayed(count: Int, unread: Boolean): Int = if (unread) count.coerceAtLeast(1) else 0

    fun label(count: Int): String = if (count > 99) "99+" else count.coerceAtLeast(1).toString()
}
