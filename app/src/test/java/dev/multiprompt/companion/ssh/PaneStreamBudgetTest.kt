package dev.multiprompt.companion.ssh

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PaneStreamBudgetTest {
    private data class Reading(val bytes: Int, val frames: List<String>, val expected: List<String>)

    private fun capture(root: File, visibleOnly: Boolean): Reading {
        File(root, "tick").writeText("0")
        val wire = File(root, "wire")
        val process = ProcessBuilder("/bin/sh", "-c", TmuxCommands.stream("work", visibleOnly = visibleOnly)).apply {
            environment()["PATH"] = root.path + File.pathSeparator + System.getenv("PATH")
            environment()["HOME"] = root.path
            environment()["FIXTURE"] = root.path
            redirectOutput(wire)
            redirectError(File(root, "stderr"))
        }.start()
        try {
            assertTrue("stream timed out", process.waitFor(30, TimeUnit.SECONDS))
            assertEquals("stream must reach the fixture's session end", 1, process.exitValue())
            assertTrue(File(root, "stderr").readText().contains("tmux session ended"))
            val frames = wire.readLines().filter { it.startsWith(TmuxCommands.SNAPSHOT_PREFIX) }
                .map { it.removePrefix(TmuxCommands.SNAPSHOT_PREFIX) }
            assertEquals(60, frames.size)
            assertTrue(frames.all { it.startsWith(TmuxCommands.SNAPSHOT_PACKED_MARKER) })
            val prefix = if (visibleOnly) "visible" else "full"
            val expected = (0 until 60).map { File(root, "$prefix-$it").readText() }
            frames.zip(expected).forEach { (frame, text) -> assertEquals(text, TmuxText.decodeSnapshot(frame)) }
            return Reading(wire.length().toInt(), frames, expected)
        } finally {
            process.destroyForcibly().waitFor()
        }
    }

    private fun decode(reading: Reading): Long = measureNanoTime {
        reading.frames.forEachIndexed { index, frame ->
            val decoded = TmuxText.decodeSnapshot(frame)
            assertEquals(reading.expected[index].length, decoded.length)
            assertTrue(decoded.endsWith("STREAM_REPLY_$index\n"))
        }
    }

    @Test
    fun streamingMinuteUsesLessWireDataAndRunsThePhoneDecoderOnEveryFrame() {
        assumeTrue(System.getenv("GITHUB_ACTIONS") == "true")
        val root = Files.createTempDirectory("pane-stream-budget").toFile()
        try {
            val history = (0 until 5000).joinToString("\n", postfix = "\n") {
                "tool output line %04d: app/src/main/java/dev/multiprompt/companion/reader/ChatHistory.kt passed".format(it)
            }
            repeat(60) { index ->
                val visible = (0 until 31).joinToString("\n", postfix = "\n") { "reply context line $it" } + "STREAM_REPLY_$index\n"
                File(root, "visible-$index").writeText(visible)
                File(root, "full-$index").writeText((history + visible).takeLast(524288))
            }
            File(root, "tmux").apply {
                writeText("""
                    #!/bin/sh
                    tick=${'$'}(cat "${'$'}FIXTURE/tick")
                    case "${'$'}1" in
                        has-session) [ "${'$'}tick" -lt 60 ] ;;
                        display-message) printf '0\n' ;;
                        capture-pane)
                            case " ${'$'}* " in
                                *' -S -5000 '*) cat "${'$'}FIXTURE/full-${'$'}tick" ;;
                                *) cat "${'$'}FIXTURE/visible-${'$'}tick" ;;
                            esac ;;
                    esac
                """.trimIndent())
                assertTrue(setExecutable(true))
            }
            File(root, "sleep").apply {
                writeText("#!/bin/sh\ntick=${'$'}(cat \"${'$'}FIXTURE/tick\"); echo ${'$'}((tick + 1)) > \"${'$'}FIXTURE/tick\"\n")
                assertTrue(setExecutable(true))
            }
            val before = capture(root, visibleOnly = false)
            val after = capture(root, visibleOnly = true)
            repeat(3) { decode(before); decode(after) }
            val beforeTimes = mutableListOf<Long>()
            val afterTimes = mutableListOf<Long>()
            repeat(5) { index ->
                if (index % 2 == 0) { beforeTimes += decode(before); afterTimes += decode(after) }
                else { afterTimes += decode(after); beforeTimes += decode(before) }
            }
            assertTrue("visible pane did not cut wire bytes", after.bytes < before.bytes / 10)
            val report = """{"fixture":"5000 scrollback lines, 32 visible lines, 60 changing frames at modeled 1 Hz", "runtime":"GitHub JVM, production Android snapshot decoder, not physical phone wall time", "runs":5, "before_bytes_per_minute":${before.bytes}, "after_bytes_per_minute":${after.bytes}, "before_decode_ns_per_minute":${beforeTimes.sorted()[2]}, "after_decode_ns_per_minute":${afterTimes.sorted()[2]}, "before_decode_runs_ns":$beforeTimes, "after_decode_runs_ns":$afterTimes, "decode_errors":0, "frames_per_minute":60}"""
            File("build/reports/pane-stream-budget.json").apply { parentFile.mkdirs(); writeText(report + "\n") }
            println("PANE_STREAM_BUDGET $report")
        } finally {
            root.deleteRecursively()
        }
    }
}
