package dev.multiprompt.companion.reader

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotCostSplitTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun splitsThePerSnapshotCostOfALongChatByStage() {
        val table = ArrayList<String>()
        for (size in SIZES) {
            for (growing in listOf(false, true)) table.add(measure(size, growing))
        }
        table.forEach { println(it) }
    }

    private fun measure(targetBytes: Int, growing: Boolean): String {
        val size = "${targetBytes / 1024}KB"
        val mode = if (growing) "growing" else "identical"
        val cache = ChatHistoryCache(temporary.newFolder("cache-$size-$mode"))
        val base = LongChatSnapshot.built(0L, targetBytes).size
        assertTrue("the $size snapshot must land close to its target", kotlin.math.abs(base - targetBytes) <= targetBytes / 50)
        val frame = Timed(SNAPSHOTS)
        val parse = Timed(SNAPSHOTS)
        val merge = Timed(SNAPSHOTS)
        val store = Timed(SNAPSHOTS)
        val project = Timed(SNAPSHOTS)
        val guard = Timed(SNAPSHOTS)
        val repeat = Timed(SNAPSHOTS)
        val fingerprint = SnapshotFingerprint()
        val projected = ArrayList<Int>()
        var history = ChatHistory()
        var lastSize = 0
        var repeats = 0
        repeat(SNAPSHOTS) { snapshot ->
            val id = snapshot + 1L
            val built = LongChatSnapshot.built(id, targetBytes, if (growing) snapshot else 0)
            lastSize = built.size
            val framer = ChatHistoryProtocol.Framer()
            val reply = frame.record(snapshot) { framer.feed(built.bytes + LongChatSnapshot.newline()).single() }
            val page = requireNotNull(parse.record(snapshot) { ChatHistoryProtocol.parse(reply, id, LongChatSnapshot.identity) })
            val parsed = merge.record(snapshot) { history.apply(page) }
            val repeated = guard.record(snapshot) { fingerprint.same(reply) }
            if (repeated) repeats++
            val next = if (repeated) {
                repeat.record(snapshot) { ChatHistoryProtocol.repeated(history) }
                history
            } else parsed
            val changed = next != history
            store.record(snapshot) { if (changed) cache.save("mubo-453", next) }
            history = next
            projected.add(project.record(snapshot) { next.projectMessages(now = NOW, sent = null) }.size)
        }
        if (growing) {
            assertTrue("each snapshot must add exactly one message", projected.zipWithNext().all { (a, b) -> b == a + 1 })
            assertEquals("a growing snapshot must never count as repeated", 0, repeats)
        } else {
            assertEquals("identical snapshots must project the same messages", 1, projected.toSet().size)
            assertEquals("every snapshot after the first must be skipped", SNAPSHOTS - 1, repeats)
        }
        assertTrue("the long chat must project many messages", projected.last() > 100)
        report("size=$size mode=$mode snapshots=$SNAPSHOTS base_bytes=$base last_bytes=$lastSize", listOf(
            FRAME to frame, PARSE to parse, MERGE to merge, STORE to store, PROJECT to project, GUARD to guard, REPEAT to repeat,
        ))
        val unchanged = if (growing) "" else " unchanged_before=${parse.add(merge)} unchanged_after=${guard.add(repeat)}"
        return "MUBO453_TABLE size=$size mode=$mode messages=${projected.last()} base_bytes=$base last_bytes=$lastSize" +
            " frame=${frame.median()} parse=${parse.median()} merge=${merge.median()} cache=${store.median()}" +
            " project=${project.median()} guard=${guard.median()} repeat=${repeat.median()} repeats=$repeats" +
            " total=${frame.add(parse, merge, store, project)}$unchanged"
    }

    private class Timed(private val count: Int) {
        private val nanos = LongArray(count)
        private val allocated = LongArray(count)

        fun <T> record(snapshot: Int, block: () -> T): T {
            val before = JvmAllocation.current()
            val start = System.nanoTime()
            val value = block()
            nanos[snapshot] = System.nanoTime() - start
            allocated[snapshot] = JvmAllocation.current() - before
            return value
        }

        fun median(): String = millis(p95Sorted()[count / 2])

        fun add(vararg others: Timed): String = millis(nanos.indices.sumOf { index ->
            (nanos[index] + others.sumOf { other -> other.nanos[index] }) / count
        })

        fun line(stage: Stage): String {
            val sorted = p95Sorted()
            val kib = if (JvmAllocation.available) format(allocated.sum().toDouble() / count / 1024.0) else "na"
            return "MUBO453_COST stage=${stage.name} thread=${stage.thread}" +
                " mean_ms=${millis(nanos.average().toLong())} median_ms=${millis(sorted[count / 2])}" +
                " p95_ms=${millis(sorted[((count - 1) * 95) / 100])} kib_per_snapshot=$kib site=${stage.site}"
        }

        private fun p95Sorted(): LongArray = nanos.sortedArray()
    }

    private fun report(prefix: String, stages: List<Pair<Stage, Timed>>) {
        for ((stage, timed) in stages) println("$prefix ${timed.line(stage)}")
        runCatching {
            val output = File(File(System.getProperty("user.dir"), "build"), "mubo-453")
            output.mkdirs()
            File(output, "reader-stages.txt").appendText(prefix + "\n" +
                stages.joinToString("") { (stage, timed) -> timed.line(stage) + "\n" })
        }
    }

    private data class Stage(val name: String, val thread: String, val site: String)

    private companion object {
        const val SNAPSHOTS = 50
        const val NOW = 1_700_100_000.0
        val SIZES = listOf(95 * 1024, 500 * 1024)
        val FRAME = Stage("frame", "Dispatchers.IO",
            "ReaderStreamPump.pump -> SshRepository.streamChatHistory line() -> ChatHistoryProtocol.Framer.feed ChatHistoryProtocol.kt:208")
        val PARSE = Stage("parse", "Dispatchers.IO",
            "ChatHistoryProtocol.parse ChatHistoryProtocol.kt:118 called at ChatHistoryConnection.kt:380")
        val MERGE = Stage("merge", "Dispatchers.IO",
            "ChatHistory.apply ChatHistory.kt:263 plus data class equality at ChatHistoryConnection.kt:384")
        val STORE = Stage("cache", "Dispatchers.IO",
            "ChatHistoryCache.save ChatHistoryCache.kt:53 called at ChatHistoryConnection.kt:546")
        val PROJECT = Stage("project", "Dispatchers.IO when measured here, Main in the app",
            "ChatHistory.projectMessages ChatHistory.kt:67 called in composition at ChatHistoryList.kt:122")
        val GUARD = Stage("guard", "Dispatchers.IO",
            "SnapshotFingerprint.same SnapshotFingerprint.kt:6 before ChatHistoryProtocol.parse at ChatHistoryConnection.kt:381")
        val REPEAT = Stage("repeat", "Dispatchers.IO",
            "ChatHistoryProtocol.repeated ChatHistoryProtocol.kt:118, the page that changes nothing")
    }
}

private fun millis(nanos: Long): String = format(nanos / 1_000_000.0)

private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
