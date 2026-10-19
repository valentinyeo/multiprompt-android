package dev.multiprompt.companion.account

class VisitDwell(private val startedAt: Long, private val startedElapsed: Long) {
    private var recorded = false

    fun visitAt(elapsed: Long): Long? {
        if (recorded || elapsed - startedElapsed < DWELL) return null
        recorded = true
        return startedAt
    }
}
