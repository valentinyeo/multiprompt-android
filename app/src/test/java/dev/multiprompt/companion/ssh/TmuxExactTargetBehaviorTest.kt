package dev.multiprompt.companion.ssh

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
        Files.write(adapter, "#!/bin/sh\nexec /usr/bin/tmux -S '${socket}' \"\$@\"\n".toByteArray(Charsets.UTF_8))
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

    private fun describe(value: String): String {
        val escaped = buildString {
            for (char in value) {
                append(when (char) {
                    '\\' -> "\\\\"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> if (char.isISOControl()) "\\u%04x".format(char.code) else char.toString()
                })
            }
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        return "length=${value.length} escaped=$escaped utf8hex=" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun describe(command: String, result: Result): String =
        "command=${describe(command)} status=${result.status} output=${describe(result.output)}"

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
        assertFalse("Neighbor must never receive input", Files.exists(receipt) && String(Files.readAllBytes(receipt), Charsets.UTF_8).contains("FORBIDDEN_1230"))
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
        assertFalse("Glob neighbor must never receive input", Files.exists(receipt) && String(Files.readAllBytes(receipt), Charsets.UTF_8).contains("FORBIDDEN_GLOB_1230"))
    }

    @Test
    fun missingSessionControlsDoNotAffectNeighbor() {
        val receipt = neighbor("verify-session-neighbor")
        assertTrue(run(TmuxCommands.action("verify-session", TmuxAction.ENTER)).status != 0)
        assertTrue(run(TmuxCommands.renameWindow("verify-session", "wrong")).status != 0)
        assertTrue(run(TmuxCommands.stream("verify-session")).status != 0)
        assertEquals(0, run(TmuxCommands.dissolveSession("verify-session")).status)
        assertEquals(0, run("tmux has-session -t '=verify-session-neighbor'").status)
        assertFalse(Files.exists(receipt) && String(Files.readAllBytes(receipt), Charsets.UTF_8).isNotEmpty())
    }

    @Test
    fun creationAndSelectionKeepSpecialNamesLiteral() {
        data class Case(val raw: String, val stored: String, val printed: String)
        val cases = listOf(
            Case("literal[ab]*", "literal[ab]*", "literal[ab]*"),
            Case("=literal", "=literal", "=literal"),
            Case("\$literal", "\\\$literal", "\\\\\$literal"),
            Case("quote'name", "quote'name", "quote'name"),
        )
        val creations = mutableListOf<Pair<String, Result>>()
        for (case in cases) {
            val command = TmuxCommands.createShellSession(case.raw, dir.toString())
            val creation = run(command)
            creations.add(command to creation)
            assertEquals(creation.output, 0, creation.status)
            assertEquals(describe(command, creation), "${TmuxCommands.CREATED_PREFIX}${case.raw}\n", creation.output)
        }
        val listingCommand = "tmux list-sessions -F '#{session_name}'"
        val listing = run(listingCommand)
        assertEquals(0, listing.status)
        val listedNames = listing.output.trim().lines().toSet()
        val controlCommand = "tmux new-session -d -P -F '#{session_id}::#{session_name}' -s '\$native_control'"
        val control = run(controlCommand)
        val expectedNames = cases.map { it.printed }.toSet()
        val details = if (expectedNames != listedNames || control.status != 0 ||
            control.output.substringAfter("::", "").trimEnd('\n', '\r') != "\\\\\$native_control") {
            val versionCommand = "tmux -V"
            buildString {
                appendLine(describe(listingCommand, listing))
                for ((command, result) in creations) appendLine(describe(command, result))
                appendLine(describe(controlCommand, control))
                appendLine(describe(versionCommand, run(versionCommand)))
            }
        } else ""
        assertEquals(details, 0, control.status)
        val controlId = control.output.substringBefore("::")
        assertTrue(details, controlId.startsWith("\$") && controlId.drop(1).isNotEmpty() && controlId.drop(1).all { it.isDigit() })
        assertEquals(details, "\\\\\$native_control", control.output.substringAfter("::", "").trimEnd('\n', '\r'))
        assertEquals(details, expectedNames, listedNames)
        for (case in cases) {
            assertEquals(case.stored, 0, run(TmuxCommands.action(case.stored, TmuxAction.ENTER)).status)
            assertEquals(case.stored, 0, run(TmuxCommands.capture(case.stored)).status)
        }
    }

    @Test
    fun streamRepublishesUnchangedSnapshotWhenAlternateChanges() {
        val state = Files.createDirectory(dir.resolve("state"))
        val producer = dir.resolve("producer.py")
        Files.write(producer, ("import pathlib, sys, time\n" +
            "d = pathlib.Path(sys.argv[1])\n" +
            "(d / 'ready').touch()\n" +
            "deadline = time.monotonic() + 20\n" +
            "while not (d / 'enter').exists() and time.monotonic() < deadline:\n" +
            "    time.sleep(0.02)\n" +
            "if (d / 'enter').exists():\n" +
            "    sys.stdout.write('\\x1b[?1049h\\x1b[2J\\x1b[HLIVE_1230')\n" +
            "    sys.stdout.flush()\n" +
            "    time.sleep(20)\n").toByteArray(Charsets.UTF_8))
        val creation = ProcessBuilder("/usr/bin/tmux", "-S", socket.toString(), "new-session", "-d", "-s", "stream-test",
            "python3 $producer $dir").redirectErrorStream(true).start()
        try {
            assertTrue("Session creation timed out", creation.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, creation.exitValue())
        } finally {
            if (creation.isAlive) creation.destroyForcibly()
            assertTrue("Session creation must terminate", creation.waitFor(5, TimeUnit.SECONDS))
        }
        val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!Files.exists(dir.resolve("ready")) && System.nanoTime() < readyDeadline) Thread.sleep(20)
        assertTrue("Pane producer must be ready", Files.exists(dir.resolve("ready")))
        Files.write(bin.resolve("tmux"), ("#!/bin/sh\n" +
            "if [ \"\$1\" = display-message ] && [ \"\$5\" = '#{alternate_on}' ] && [ ! -e '$dir/intercepted' ]; then\n" +
            "  value=\$(/usr/bin/tmux -S '$socket' \"\$@\") || exit 1\n" +
            "  [ \"\$value\" = 0 ] || exit 2\n" +
            "  : > '$dir/intercepted'; : > '$dir/enter'\n" +
            "  n=0\n" +
            "  while [ \"\$n\" -lt 100 ]; do\n" +
            "    alt=\$(/usr/bin/tmux -S '$socket' display-message -p -t '=stream-test:' '#{alternate_on}') || exit 3\n" +
            "    screen=\$(/usr/bin/tmux -S '$socket' capture-pane -p -J -t '=stream-test:') || exit 4\n" +
            "    if [ \"\$alt\" = 1 ] && printf '%s' \"\$screen\" | /bin/grep -q LIVE_1230; then\n" +
            "      printf '%s\\n' \"\$value\"; exit 0\n" +
            "    fi\n" +
            "    n=\$((n + 1)); sleep 0.05\n" +
            "  done\n" +
            "  exit 5\n" +
            "fi\n" +
            "exec /usr/bin/tmux -S '$socket' \"\$@\"\n").toByteArray(Charsets.UTF_8))
        val process = ProcessBuilder("bash", "-c", TmuxCommands.stream("stream-test")).redirectErrorStream(true).apply {
            environment()["PATH"] = "$bin:/usr/bin:/bin"
            environment()["MULTIPROMPT_STATE_DIR"] = state.toString()
            environment()["TMPDIR"] = dir.toString()
        }.start()
        val lines = LinkedBlockingQueue<String>()
        val reader = thread(isDaemon = true) {
            process.inputStream.bufferedReader().use { input ->
                while (true) lines.put(input.readLine() ?: break)
            }
        }
        val transcript = mutableListOf<String>()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (transcript.count { it == "${TmuxCommands.ALT_PREFIX}1" } < 4) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                val line = lines.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                transcript.add(line)
            }
            val details = "stream transcript: $transcript"
            assertEquals(details, 1, transcript.count { it == "${TmuxCommands.ALT_PREFIX}0" })
            assertEquals(details, 4, transcript.count { it == "${TmuxCommands.ALT_PREFIX}1" })
            val frames = mutableListOf<Pair<String, String>>()
            var alternate = ""
            for (line in transcript) {
                when {
                    line.startsWith(TmuxCommands.ALT_PREFIX) -> alternate = line.removePrefix(TmuxCommands.ALT_PREFIX)
                    line.startsWith(TmuxCommands.ETA_PREFIX) -> assertEquals(details, "", line.removePrefix(TmuxCommands.ETA_PREFIX))
                    line.startsWith(TmuxCommands.SNAPSHOT_PREFIX) -> frames.add(alternate to line.removePrefix(TmuxCommands.SNAPSHOT_PREFIX))
                    else -> throw AssertionError(details)
                }
            }
            assertEquals(details, listOf("0", "1"), frames.map { it.first })
            fun decode(hex: String): ByteArray {
                assertEquals(details, 0, hex.length % 2)
                return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            val first = decode(frames[0].second)
            val second = decode(frames[1].second)
            assertTrue(details, String(first, Charsets.UTF_8).contains("LIVE_1230"))
            assertArrayEquals(details, first, second)
            assertEquals(details, listOf("alt0", "eta", "snapshot", "alt1", "eta", "snapshot", "alt1", "alt1", "alt1"),
                transcript.map { line ->
                    when {
                        line == "${TmuxCommands.ALT_PREFIX}0" -> "alt0"
                        line == "${TmuxCommands.ALT_PREFIX}1" -> "alt1"
                        line.startsWith(TmuxCommands.ETA_PREFIX) -> "eta"
                        else -> "snapshot"
                    }
                })
        } finally {
            val descendants = process.toHandle().descendants().use { it.toList() }
            descendants.asReversed().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            assertTrue("Stream must terminate", process.waitFor(5, TimeUnit.SECONDS))
            descendants.forEach { it.onExit().get(5, TimeUnit.SECONDS) }
            reader.join(5000)
            assertFalse("Stream reader must terminate", reader.isAlive)
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
        Files.write(bin.resolve("tmux"),
            ("#!/bin/sh\n" +
                "if [ \"\$1\" = list-sessions ]; then\n" +
                "  /usr/bin/tmux -S '$socket' \"\$@\" > '$dir/snapshot' || exit 1\n" +
                "  /usr/bin/tmux -S '$socket' kill-session -t '=b-vanished' || exit 1\n" +
                "  cat '$dir/snapshot'\n" +
                "else exec /usr/bin/tmux -S '$socket' \"\$@\"; fi\n").toByteArray(Charsets.UTF_8))
        val result = run(TmuxParser.command())
        val rows = TmuxParser.parse("fixture", result.output)
        val sessions = rows.associateBy { it.name }
        val stable = sessions["a-stable"]?.preview.orEmpty()
        val neighbor = sessions["b-vanished-neighbor"]?.preview.orEmpty()
        val details = if (result.status != 0 || rows.size != 2 ||
            sessions.keys != setOf("a-stable", "b-vanished-neighbor") ||
            !stable.contains("STABLE_SCREEN_1230") || !neighbor.contains("NEIGHBOR_SCREEN_1230")) {
            buildString {
                appendLine(describe("production listing", result))
                for (row in rows) appendLine("row name=${describe(row.name)} preview=${describe(row.preview)}")
                val snapshot = dir.resolve("snapshot")
                appendLine("snapshot=" + if (Files.exists(snapshot)) describe(String(Files.readAllBytes(snapshot), Charsets.UTF_8)) else "missing")
                val versionCommand = "tmux -V"
                appendLine(describe(versionCommand, run(versionCommand)))
                val format = "#{session_name}::#{session_id}::#{pane_id}::#{window_width}::#{window_height}::#{alternate_on}::#{pane_dead}::#{cursor_x}::#{cursor_y}"
                for (name in listOf("a-stable", "b-vanished-neighbor")) {
                    val metadata = "tmux display-message -p -t '=$name:' -F '$format'"
                    val plain = "tmux capture-pane -p -t '=$name:'"
                    val history = "tmux capture-pane -p -J -S -12 -t '=$name:'"
                    for (command in listOf(metadata, plain, history)) appendLine(describe(command, run(command)))
                }
                val missing = "tmux display-message -p -t '=b-vanished:' -F '$format'"
                appendLine(describe(missing, run(missing)))
            }
        } else ""
        assertEquals(details, 0, result.status)
        assertEquals(details, 2, rows.size)
        assertEquals(details, 2, result.output.lineSequence().count { it.startsWith(TmuxParser.PREVIEW_PREFIX) })
        assertEquals(details, setOf("a-stable", "b-vanished-neighbor"), sessions.keys)
        assertTrue(details, sessions.getValue("a-stable").preview.contains("STABLE_SCREEN_1230"))
        assertTrue(details, sessions.getValue("b-vanished-neighbor").preview.contains("NEIGHBOR_SCREEN_1230"))
    }
}
