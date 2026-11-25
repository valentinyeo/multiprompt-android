package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.reader.ChatPollGate
import dev.multiprompt.companion.reader.paneFrames
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PaneStreamBudgetTest {
    private data class Reading(val bytes: Long, val captures: Int, val frames: List<String>, val expected: List<String>)

    private fun fixture(root: File) {
        File(root, "tick").writeText("0")
        File(root, "captures").writeText("")
        repeat(60) { index ->
            File(root, "full-$index").writeText(
                (0 until 5000).joinToString("\n", postfix = "\n") {
                    "tool output line %04d: app/src/main/java/dev/multiprompt/companion/reader/ChatHistory.kt passed".format(it)
                } + "STREAM_REPLY_$index\n",
            )
        }
        File(root, "tmux").apply {
            writeText(
                """
                #!/bin/sh
                tick=${'$'}(cat "${'$'}FIXTURE/tick")
                case "${'$'}1" in
                    has-session) [ "${'$'}tick" -lt 60 ] ;;
                    display-message) printf '0\n' ;;
                    capture-pane)
                        echo "${'$'}tick" >> "${'$'}FIXTURE/captures"
                        cat "${'$'}FIXTURE/full-${'$'}tick" ;;
                esac
                """.trimIndent(),
            )
            assertTrue(setExecutable(true))
        }
        File(root, "sleep").apply {
            writeText("#!/bin/sh\ntick=${'$'}(cat \"${'$'}FIXTURE/tick\"); echo ${'$'}((tick + 1)) > \"${'$'}FIXTURE/tick\"\n")
            assertTrue(setExecutable(true))
        }
    }

    private fun minute(root: File): Reading {
        val wire = File(root, "wire")
        val process = ProcessBuilder("/bin/sh", "-c", TmuxCommands.stream("work")).apply {
            environment()["PATH"] = root.path + File.pathSeparator + System.getenv("PATH")
            environment()["HOME"] = root.path
            environment()["FIXTURE"] = root.path
            redirectOutput(wire)
            redirectError(File(root, "stderr"))
        }.start()
        try {
            assertTrue("stream timed out", process.waitFor(60, TimeUnit.SECONDS))
            assertEquals("stream must reach the fixture's session end", 1, process.exitValue())
            assertTrue(File(root, "stderr").readText().contains("tmux session ended"))
            val frames = wire.readLines().filter { it.startsWith(TmuxCommands.SNAPSHOT_PREFIX) }
                .map { it.removePrefix(TmuxCommands.SNAPSHOT_PREFIX) }
            assertEquals(60, frames.size)
            assertTrue(frames.all { it.startsWith(TmuxCommands.SNAPSHOT_PACKED_MARKER) })
            val expected = (0 until 60).map { File(root, "full-$it").readText() }
            frames.zip(expected).forEach { (frame, text) -> assertEquals(text, TmuxText.decodeSnapshot(frame)) }
            return Reading(wire.length(), File(root, "captures").readLines().size, frames, expected)
        } finally {
            process.destroyForcibly().waitFor()
        }
    }

    private fun historyViewMinute(root: File): Reading {
        val chat = ChatPollGate()
        val pane = ChatPollGate()
        val lease = chat.acquire()
        val wire = File(root, "history-wire")
        val decoded = try {
            runBlocking {
                withTimeoutOrNull(5_000L) {
                    paneFrames(pane.active) {
                        File(root, "tick").writeText("0")
                        File(root, "captures").writeText("")
                        val process = ProcessBuilder("/bin/sh", "-c", TmuxCommands.stream("work")).apply {
                            environment()["PATH"] = root.path + File.pathSeparator + System.getenv("PATH")
                            environment()["HOME"] = root.path
                            environment()["FIXTURE"] = root.path
                            redirectOutput(wire)
                            redirectError(File(root, "stderr"))
                        }.start()
                        try {
                            process.waitFor(60, TimeUnit.SECONDS)
                        } finally {
                            process.destroyForcibly().waitFor()
                        }
                    }
                }
            }
        } finally {
            lease.close()
            assertTrue(!chat.active.value)
        }
        assertEquals("the chat view opened a pane channel", null, decoded)
        val frames = wire.takeIf(File::exists)?.readLines()?.filter { it.startsWith(TmuxCommands.SNAPSHOT_PREFIX) }.orEmpty()
        return Reading(wire.takeIf(File::exists)?.length() ?: 0L, File(root, "captures").readLines().count { it.isNotBlank() }, frames, emptyList())
    }

    private fun decode(reading: Reading): Long = measureNanoTime {
        reading.frames.forEachIndexed { index, frame ->
            val decoded = TmuxText.decodeSnapshot(frame)
            assertEquals(reading.expected[index].length, decoded.length)
            assertTrue(decoded.endsWith("STREAM_REPLY_$index\n"))
        }
    }

    @Test
    fun theChatViewSpendsNoWireBytesOrDecodeTimeOnThePane() {
        assumeTrue(System.getenv("GITHUB_ACTIONS") == "true")
        val root = Files.createTempDirectory("pane-stream-budget").toFile()
        try {
            fixture(root)
            val before = minute(root)
            File(root, "tick").writeText("0")
            File(root, "captures").writeText("")
            val after = historyViewMinute(root)
            repeat(3) { decode(before) }
            val beforeTimes = mutableListOf<Long>()
            repeat(5) { beforeTimes += decode(before) }
            assertEquals(60, before.frames.size)
            assertEquals(60, before.captures)
            assertEquals("the chat view still captured the pane", 0, after.captures)
            assertEquals("the chat view still received pane frames", 0, after.frames.size)
            assertEquals(0L, after.bytes)
            val report = """{"fixture":"5000 scrollback lines, 60 changing frames at modeled 1 Hz", "runtime":"GitHub JVM, production Android snapshot decoder, not physical phone wall time", "runs":5, "before_bytes_per_minute":${before.bytes}, "after_bytes_per_minute":${after.bytes}, "before_captures_per_minute":${before.captures}, "after_captures_per_minute":${after.captures}, "before_decode_ns_per_minute":${beforeTimes.sorted()[2]}, "after_decode_ns_per_minute":0, "before_decode_runs_ns":$beforeTimes, "after_decode_runs_ns":[0,0,0,0,0], "decode_errors":0, "frames_per_minute":60}"""
            File("build/reports/pane-stream-budget.json").apply { parentFile.mkdirs(); writeText(report + "\n") }
            println("PANE_STREAM_BUDGET $report")
        } finally {
            root.deleteRecursively()
        }
    }
}
