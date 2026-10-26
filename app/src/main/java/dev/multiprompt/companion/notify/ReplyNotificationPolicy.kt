package dev.multiprompt.companion.notify

object ReplyNotificationPolicy {
    data class Plan(val notify: List<String>, val cancel: Set<String>)

    fun plan(
        previous: Map<String, Int>?,
        current: Map<String, Int>,
        frontKey: String?,
        active: Set<String>,
    ): Plan {
        val cancel = active.filterTo(linkedSetOf()) { key -> key == frontKey || (current[key] ?: 0) <= 0 }
        if (previous == null) return Plan(emptyList(), cancel)
        val notify = current
            .filter { (key, count) -> key != frontKey && count > (previous[key] ?: 0) }
            .keys
            .sorted()
        return Plan(notify, cancel)
    }
}
