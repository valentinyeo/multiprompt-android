package dev.multiprompt.companion.reader

import java.lang.management.ManagementFactory

internal object JvmAllocation {
    private val method = runCatching {
        Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
    }.getOrNull()
    private val bean = runCatching { ManagementFactory.getThreadMXBean() }.getOrNull()

    val available: Boolean get() = method != null && bean != null

    fun current(): Long {
        val call = method ?: return 0L
        val target = bean ?: return 0L
        return runCatching { (call.invoke(target) as? Long) ?: 0L }.getOrDefault(0L)
    }
}
