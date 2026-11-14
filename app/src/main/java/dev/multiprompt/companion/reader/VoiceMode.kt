package dev.multiprompt.companion.reader

object VoiceMode {
    const val STABLE_MS = 2500L

    data class Memory(
        val baselined: Boolean = false,
        val seen: Set<String> = emptySet(),
        val lastSpoken: String = "",
    )

    data class Step(val memory: Memory, val speak: List<ReadAloud.Spoken>)

    class Tracker {
        var memory = Memory()
    }

    class Playback {
        private val open = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val counter = java.util.concurrent.atomic.AtomicLong()

        fun begin(flush: Boolean): String {
            if (flush) open.clear()
            val id = "reply-${counter.incrementAndGet()}"
            open.add(id)
            return id
        }

        fun end(id: String): Boolean {
            open.remove(id)
            return open.isEmpty()
        }

        fun clear() = open.clear()
    }

    fun observe(
        memory: Memory,
        replies: List<ReadAloud.Spoken>,
        ready: Boolean,
        enabled: Boolean,
        stable: Boolean,
    ): Step {
        if (!ready) return Step(memory, emptyList())
        val keys = replies.map { it.key }.toSet()
        if (!memory.baselined) {
            return Step(Memory(true, keys, replies.firstOrNull()?.text.orEmpty()), emptyList())
        }
        if (!enabled) return Step(memory.copy(seen = memory.seen + keys), emptyList())
        if (!stable) return Step(memory, emptyList())
        val boundary = replies.indexOfFirst { it.key in memory.seen }
        val fresh = when {
            boundary >= 0 -> replies.take(boundary)
            memory.seen.isEmpty() -> replies
            else -> replies.take(1)
        }
        var last = memory.lastSpoken
        val speak = ArrayList<ReadAloud.Spoken>()
        for (reply in fresh.asReversed()) {
            if (reply.text == last) continue
            speak.add(reply)
            last = reply.text
        }
        return Step(Memory(true, memory.seen + keys, last), speak)
    }
}
