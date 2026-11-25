package dev.multiprompt.companion.ssh

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class TmuxSignatureBehaviorTest {
    private lateinit var dir: Path
    private lateinit var bin: Path

    private data class Result(val status: Int, val output: String)

    @Before
    fun setup() {
        assumeTrue(System.getenv("GITHUB_ACTIONS") == "true")
        dir = Files.createTempDirectory("mp-tmux-signature-")
        val socket = dir.resolve("socket")
        bin = Files.createDirectory(dir.resolve("bin"))
        val adapter = bin.resolve("tmux")
        Files.write(adapter, "#!/bin/sh\nexec /usr/bin/tmux -S '${socket}' \"\$@\"\n".toByteArray(Charsets.UTF_8))
        assertTrue(adapter.toFile().setExecutable(true))
    }

    @After
    fun cleanup() {
        if (!::dir.isInitialized) return
        run("tmux kill-server || true")
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun run(command: String): Result {
        val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).apply {
            environment()["PATH"] = "$bin:/usr/bin:/bin"
        }.start()
        process.outputStream.close()
        assertTrue("Command timed out: $command", process.waitFor(20, TimeUnit.SECONDS))
        return Result(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun signature(): String {
        val result = run(TmuxParser.signatureCommand())
        assertEquals(result.output, 0, result.status)
        assertTrue(result.output, TmuxParser.hasEnvelope(result.output))
        assertEquals(result.output, null, TmuxParser.error(result.output))
        return TmuxParser.parseSignature(result.output)
            ?: throw AssertionError("No checksum line in: ${result.output}")
    }

    private fun newSession(name: String, screen: String) {
        assertEquals(0, run("tmux new-session -d -s '$name' 'printf $screen; exec cat'").status)
        var ready = false
        while (!ready) {
            ready = run("tmux capture-pane -p -t '=$name:'").output.contains(screen)
            if (!ready) {
                assertEquals(0, run("tmux has-session -t '=$name'").status)
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun anUntouchedInboxCostsOneChecksumLineAndKeepsTheSameChecksum() {
        newSession("sig-idle", "IDLE_SCREEN_5470")
        val first = signature()
        val second = signature()
        val whole = run(TmuxParser.signatureCommand()).output.trimEnd().lines()

        assertEquals(first, second)
        assertEquals(3, whole.size)
        assertEquals(TmuxParser.START_MARKER, whole[0])
        assertEquals(TmuxParser.END_MARKER, whole[2])
        assertEquals(first, whole[1])
        assertTrue(first, whole.joinToString("\n").length < 80)
    }

    @Test
    fun aNewSessionChangesTheChecksum() {
        newSession("sig-first", "FIRST_SCREEN_5470")
        val before = signature()
        newSession("sig-second", "SECOND_SCREEN_5470")

        assertNotEquals(before, signature())
    }

    @Test
    fun newPaneOutputChangesTheChecksum() {
        newSession("sig-output", "OUTPUT_ONE_5470")
        val before = signature()
        assertEquals(0, run("tmux send-keys -t '=sig-output:' 'printf OUTPUT_TWO_5470' Enter").status)
        var ready = false
        while (!ready) {
            ready = run("tmux capture-pane -p -t '=sig-output:'").output.contains("OUTPUT_TWO_5470")
            if (!ready) Thread.sleep(20)
        }
        val after = signature()

        assertNotEquals(before, after)
    }

    @Test
    fun aKilledSessionChangesTheChecksum() {
        newSession("sig-gone", "GONE_SCREEN_5470")
        newSession("sig-kept", "KEPT_SCREEN_5470")
        val before = signature()
        assertEquals(0, run("tmux kill-session -t '=sig-gone:'").status)

        assertNotEquals(before, signature())
    }
}
