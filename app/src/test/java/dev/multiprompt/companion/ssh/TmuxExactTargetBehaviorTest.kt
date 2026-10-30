package dev.multiprompt.companion.ssh

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class TmuxExactTargetBehaviorTest {
    private lateinit var dir: Path
    private lateinit var socket: Path
    private lateinit var bin: Path

    private data class Result(val status: Int, val output: String)

    @Before
    fun setup() {
        assumeTrue(System.getenv("GITHUB_ACTIONS") == "true")
        dir = Files.createTempDirectory("mp-tmux-exact-")
        socket = dir.resolve("socket")
        bin = Files.createDirectory(dir.resolve("bin"))
        val adapter = bin.resolve("tmux")
        Files.writeString(adapter, "#!/bin/sh\nexec /usr/bin/tmux -S '${socket}' \"\$@\"\n")
        assertTrue(adapter.toFile().setExecutable(true))
    }

    @After
    fun cleanup() {
        if (!::dir.isInitialized) return
        run("tmux kill-server || true")
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun run(command: String, input: String = ""): Result {
        val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).apply {
            environment()["PATH"] = "$bin:/usr/bin:/bin"
        }.start()
        process.outputStream.bufferedWriter().use { it.write(input) }
        assertTrue("Command timed out: $command", process.waitFor(15, TimeUnit.SECONDS))
        return Result(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun neighbor(name: String): Path {
        val receipt = dir.resolve("receipt")
        val command = "tmux new-session -d -s '$name' 'printf CANARY_SCREEN_1230; exec cat >> $receipt'"
        assertEquals(0, run(command).status)
        var screen = ""
        repeat(50) {
            if (!screen.contains("CANARY_SCREEN_1230")) {
                screen = run("tmux capture-pane -p -t '=$name:'").output
                if (!screen.contains("CANARY_SCREEN_1230")) Thread.sleep(100)
            }
        }
        assertTrue("Neighbor must be ready", screen.contains("CANARY_SCREEN_1230"))
        return receipt
    }

    @Test
    fun missingPrefixNeverReceivesPromptOrLeaksCapture() {
        val receipt = neighbor("verify-session-neighbor")
        val send = run(TmuxCommands.pastePrompt("verify-session"), "FORBIDDEN_1230")
        val capture = run(TmuxCommands.capture("verify-session"))
        assertTrue("Missing session must reject prompt", send.status != 0)
        assertFalse("Neighbor must never receive input", Files.exists(receipt) && Files.readString(receipt).contains("FORBIDDEN_1230"))
        assertFalse("Neighbor screen must not appear", capture.output.contains("CANARY_SCREEN_1230"))
    }

    @Test
    fun missingPrefixNeverCapturesNeighbor() {
        neighbor("verify-session-neighbor")
        val capture = run(TmuxCommands.capture("verify-session"))
        assertFalse("Neighbor screen must not appear", capture.output.contains("CANARY_SCREEN_1230"))
    }

    @Test
    fun missingGlobNeverReceivesPrompt() {
        val receipt = neighbor("glob-neighbor")
        val send = run(TmuxCommands.pastePrompt("glob-*"), "FORBIDDEN_GLOB_1230")
        assertTrue("Missing literal glob must reject prompt", send.status != 0)
        assertFalse("Glob neighbor must never receive input", Files.exists(receipt) && Files.readString(receipt).contains("FORBIDDEN_GLOB_1230"))
    }

    @Test
    fun missingSessionControlsDoNotAffectNeighbor() {
        val receipt = neighbor("verify-session-neighbor")
        assertTrue(run(TmuxCommands.action("verify-session", TmuxAction.ENTER)).status != 0)
        assertTrue(run(TmuxCommands.renameWindow("verify-session", "wrong")).status != 0)
        assertTrue(run(TmuxCommands.stream("verify-session")).status != 0)
        assertEquals(0, run(TmuxCommands.dissolveSession("verify-session")).status)
        assertEquals(0, run("tmux has-session -t '=verify-session-neighbor'").status)
        assertFalse(Files.exists(receipt) && Files.readString(receipt).isNotEmpty())
    }

    @Test
    fun creationAndSelectionKeepSpecialNamesLiteral() {
        val names = listOf("literal[ab]*", "=literal", "\$literal", "quote'name")
        for (name in names) {
            val creation = run(TmuxCommands.createShellSession(name, dir.toString()))
            assertEquals(creation.output, 0, creation.status)
        }
        val listing = run("tmux list-sessions -F '#{session_name}'")
        assertEquals(0, listing.status)
        assertEquals(names.toSet(), listing.output.trim().lines().toSet())
        for (name in names) {
            assertEquals(name, 0, run(TmuxCommands.action(name, TmuxAction.ENTER)).status)
            assertEquals(name, 0, run(TmuxCommands.capture(name)).status)
        }
    }

    @Test
    fun vanishedListingRowCannotAttachNeighborPreviewToAnotherSession() {
        assertEquals(0, run("tmux new-session -d -s a-stable 'printf STABLE_SCREEN_1230; exec cat'").status)
        assertEquals(0, run("tmux new-session -d -s b-vanished 'printf VANISHED_SCREEN_1230; exec cat'").status)
        assertEquals(0, run("tmux new-session -d -s b-vanished-neighbor 'printf NEIGHBOR_SCREEN_1230; exec cat'").status)
        for (name in listOf("a-stable", "b-vanished-neighbor")) {
            var screen = ""
            repeat(50) {
                if (!screen.contains("SCREEN_1230")) {
                    screen = run("tmux capture-pane -p -t '=$name:'").output
                    if (!screen.contains("SCREEN_1230")) Thread.sleep(100)
                }
            }
            assertTrue("Session must be ready: $name", screen.contains("SCREEN_1230"))
        }
        Files.writeString(bin.resolve("tmux"),
            "#!/bin/sh\n" +
                "if [ \"\$1\" = list-sessions ]; then\n" +
                "  /usr/bin/tmux -S '$socket' \"\$@\" > '$dir/snapshot' || exit 1\n" +
                "  /usr/bin/tmux -S '$socket' kill-session -t '=b-vanished' || exit 1\n" +
                "  cat '$dir/snapshot'\n" +
                "else exec /usr/bin/tmux -S '$socket' \"\$@\"; fi\n")
        val result = run(TmuxParser.command())
        assertEquals(result.output, 0, result.status)
        val rows = TmuxParser.parse("fixture", result.output)
        assertEquals(2, rows.size)
        val sessions = rows.associateBy { it.name }
        assertEquals(setOf("a-stable", "b-vanished-neighbor"), sessions.keys)
        assertTrue(sessions.getValue("a-stable").preview.contains("STABLE_SCREEN_1230"))
        assertTrue(sessions.getValue("b-vanished-neighbor").preview.contains("NEIGHBOR_SCREEN_1230"))
    }
}
