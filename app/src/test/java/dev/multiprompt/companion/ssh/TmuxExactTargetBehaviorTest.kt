package dev.multiprompt.companion.ssh

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

private const val STREAM_END = "stream-ended"
private const val PROCESS_GROUP_PREFIX = "process-group="

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

    private fun run(command: String, input: String = "", home: Path? = null): Result {
        val process = ProcessBuilder("bash", "-c", command).redirectErrorStream(true).apply {
            environment()["PATH"] = "$bin:/usr/bin:/bin"
            if (home != null) environment()["HOME"] = home.toString()
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
        var ready = false
        while (!ready) {
            val capture = run("tmux capture-pane -p -t '=$name:'")
            ready = capture.output.contains("CANARY_SCREEN_1230")
            if (!ready) {
                assertEquals("Neighbor must stay alive: ${capture.output}", 0, run("tmux has-session -t '=$name'").status)
                Thread.sleep(20)
            }
        }
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
        assertEquals(0, run(TmuxCommands.action("verify-session", TmuxAction.SCROLL_BOTTOM)).status)
        assertEquals(0, run(TmuxCommands.action("verify-session", TmuxAction.SCROLL_UP)).status)
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

    private fun readLines(process: Process): Pair<LinkedBlockingQueue<String>, Thread> {
        val lines = LinkedBlockingQueue<String>()
        val reader = thread(isDaemon = true) {
            process.inputStream.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    lines.put(line)
                }
            }
            lines.put(STREAM_END)
        }
        return lines to reader
    }

    private fun awaitProcessGroup(lines: LinkedBlockingQueue<String>): Long {
        val announcement = lines.take()
        assertTrue("Stream must announce its process group: $announcement", announcement.startsWith(PROCESS_GROUP_PREFIX))
        val announced = announcement.removePrefix(PROCESS_GROUP_PREFIX).trim().toLongOrNull()
        assertTrue("Stream process group must be valid: $announcement", announced != null && announced > 1)
        return announced ?: 0
    }

    private fun stopGroup(group: Long?, process: Process, reader: Thread) {
        val killer = group?.takeIf { it > 1 && process.isAlive }?.let { ProcessBuilder("/bin/kill", "-KILL", "--", "-$it").start() }
        killer?.waitFor()
        if (process.isAlive) process.destroyForcibly()
        process.waitFor()
        reader.join()
        if (killer != null) assertEquals("Group signal must succeed", 0, killer.exitValue())
        assertFalse("Stream must terminate", process.isAlive)
        assertFalse("Stream reader must terminate", reader.isAlive)
    }

    @Test
    fun streamRepublishesUnchangedSnapshotWhenAlternateChanges() {
        assumeTrue(runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
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
        assertEquals("Session creation must succeed", 0, creation.waitFor())
        val ready = dir.resolve("ready")
        while (!Files.exists(ready)) Thread.sleep(20)
        Files.write(bin.resolve("tmux"), ("#!/bin/sh\n" +
            "if [ \"\$1\" = display-message ] && [ \"\$5\" = '#{alternate_on}' ] && [ ! -e '$dir/intercepted' ]; then\n" +
            "  value=\$(/usr/bin/tmux -S '$socket' \"\$@\") || exit 1\n" +
            "  [ \"\$value\" = 0 ] || exit 2\n" +
            "  : > '$dir/intercepted'; : > '$dir/enter'\n" +
            "  while true; do\n" +
            "    alt=\$(/usr/bin/tmux -S '$socket' display-message -p -t '=stream-test:' '#{alternate_on}') || exit 3\n" +
            "    screen=\$(/usr/bin/tmux -S '$socket' capture-pane -p -J -t '=stream-test:') || exit 4\n" +
            "    if [ \"\$alt\" = 1 ] && printf '%s' \"\$screen\" | /bin/grep -q LIVE_1230; then\n" +
            "      printf '%s\\n' \"\$value\"; exit 0\n" +
            "    fi\n" +
            "    sleep 0.05\n" +
            "  done\n" +
            "fi\n" +
            "exec /usr/bin/tmux -S '$socket' \"\$@\"\n").toByteArray(Charsets.UTF_8))
        val gate = dir.resolve("stream-start")
        val process = ProcessBuilder("setsid", "bash", "-c", """
            pgid=${'$'}(/bin/ps -o pgid= -p ${'$'}${'$'} | tr -d ' ')
            parent_pgid=${'$'}(/bin/ps -o pgid= -p ${'$'}PPID | tr -d ' ')
            [ "${'$'}${'$'}" -gt 1 ] && [ "${'$'}pgid" = "${'$'}${'$'}" ] && [ -n "${'$'}parent_pgid" ] && [ "${'$'}pgid" != "${'$'}parent_pgid" ] || exit 1
            printf '$PROCESS_GROUP_PREFIX%s\n' "${'$'}pgid" || exit 1
            while [ ! -e '$gate' ]; do sleep 0.02; done
            exec bash -c "${'$'}1"
        """.trimIndent(), "stream", TmuxCommands.stream("stream-test")).redirectErrorStream(true).apply {
            environment()["PATH"] = "$bin:/usr/bin:/bin"
            environment()["MULTIPROMPT_STATE_DIR"] = state.toString()
            environment()["TMPDIR"] = dir.toString()
        }.start()
        val (lines, reader) = readLines(process)
        var group: Long? = null
        val transcript = mutableListOf<String>()
        try {
            group = awaitProcessGroup(lines)
            Files.write(gate, byteArrayOf())
            val alternateOne = "${TmuxCommands.ALT_PREFIX}1"
            while (transcript.count { it == alternateOne } < 4) {
                val line = lines.take()
                if (line == STREAM_END) break
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
            assertTrue(details, frames.all { it.second.startsWith(TmuxCommands.SNAPSHOT_PACKED_MARKER) })
            val first = TmuxText.decodeSnapshot(frames[0].second)
            val second = TmuxText.decodeSnapshot(frames[1].second)
            assertTrue(details, first.contains("LIVE_1230"))
            assertEquals(details, first, second)
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
            stopGroup(group, process, reader)
        }
    }

    @Test
    fun slowProcessGroupAnnouncementStillStartsTheStream() {
        val gate = dir.resolve("slow-gate")
        val process = ProcessBuilder("setsid", "bash", "-c", """
            sleep 6
            printf '$PROCESS_GROUP_PREFIX%s\n' "${'$'}${'$'}"
            while [ ! -e '$gate' ]; do sleep 0.02; done
            printf 'started\n'
        """.trimIndent()).redirectErrorStream(true).start()
        val (lines, reader) = readLines(process)
        var group: Long? = null
        try {
            group = awaitProcessGroup(lines)
            Files.write(gate, byteArrayOf())
            assertEquals("Slow child must keep running", "started", lines.take())
        } finally {
            stopGroup(group, process, reader)
        }
    }

    @Test
    fun vanishedListingRowCannotAttachNeighborPreviewToAnotherSession() {
        assertEquals(0, run("tmux new-session -d -s a-stable 'printf STABLE_SCREEN_1230; exec cat'").status)
        assertEquals(0, run("tmux new-session -d -s b-vanished 'printf VANISHED_SCREEN_1230; exec cat'").status)
        assertEquals(0, run("tmux new-session -d -s b-vanished-neighbor 'printf NEIGHBOR_SCREEN_1230; exec cat'").status)
        for (name in listOf("a-stable", "b-vanished-neighbor")) {
            var ready = false
            while (!ready) {
                val capture = run("tmux capture-pane -p -t '=$name:'")
                ready = capture.output.contains("SCREEN_1230")
                if (!ready) {
                    assertEquals("Session must stay alive: $name ${capture.output}", 0, run("tmux has-session -t '=$name'").status)
                    Thread.sleep(20)
                }
            }
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

    @Test
    fun registryChatsWithoutATerminalAppearOnceNextToTerminalChats() {
        val sid = "721fb507-2781-4e48-8b6d-debd6e2da3f3"
        neighbor("live-has-terminal")
        val home = Files.createDirectories(dir.resolve("home"))
        val runtime = Files.createDirectories(home.resolve(".multiprompt-runtime"))
        Files.write(runtime.resolve("reader.py"), """
            import json
            print(json.dumps(dict(chats=[
                dict(provider="claude", sid="$sid", name="live-desktop-new", cwd="/srv/work", activity=1700, visible=True),
                dict(provider="claude", sid="0a1b2c3d-2781-4e48-8b6d-debd6e2da3f3", name="live-has-terminal", cwd="/srv/work", activity=1800, visible=True),
                dict(provider="claude", sid="1a1b2c3d-2781-4e48-8b6d-debd6e2da3f3", name="live-headless", cwd="/srv/work", activity=1900, visible=False),
                dict(provider="claude", sid="2a1b2c3d-2781-4e48-8b6d-debd6e2da3f3", name="not-live", cwd="/srv/work", activity=2000, visible=True),
            ])))
        """.trimIndent().toByteArray(Charsets.UTF_8))
        val result = run(TmuxParser.command(), home = home)
        val sessions = TmuxParser.parse("fixture", result.output)
        val details = describe("production listing", result)

        assertEquals(details, 0, result.status)
        assertEquals(details, listOf("live-desktop-new", "live-has-terminal"), sessions.map { it.name }.sorted())
        val created = sessions.first { it.name == "live-desktop-new" }
        assertTrue(details, created.liveChat)
        assertEquals(details, sid, created.hostSessionId)
        assertEquals(details, "/srv/work", created.workingDirectory)
        assertEquals(details, "", sessions.first { it.name == "live-has-terminal" }.hostSessionId)
    }
}
