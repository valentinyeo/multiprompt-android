package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.AgentHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class TmuxCommandsTest {
    @Test
    fun nativeSkillsQuotesTheSessionAndTheWorkingDirectory() {
        assertEquals("python3 - 'live-abc'", TmuxCommands.nativeSkills("live-abc"))
        assertEquals("python3 - 'live-abc'", TmuxCommands.nativeSkills("live-abc", "  "))
        val command = TmuxCommands.nativeSkills("live-abc", "/home/me/it's here; reboot")
        assertEquals("python3 - 'live-abc' '/home/me/it'\"'\"'s here; reboot'", command)
    }

    @Test
    fun captureQuotesTheCompleteTarget() {
        val command = TmuxCommands.capture("a'; reboot")

        val target = "'=a'\"'\"'; reboot:'"
        assertTrue(command.contains("tmux capture-pane -p -J -S -5000 -t $target"))
        assertTrue(command.contains("#{alternate_on}"))
        assertTrue(command.contains("then tmux capture-pane -p -J -t $target"))
        assertFalse(command.contains("; reboot;"))
    }

    @Test
    fun promptCommandReadsContentFromStdin() {
        val command = TmuxCommands.pastePrompt("codex-main")

        assertTrue(command.contains("tmux load-buffer"))
        assertTrue(command.contains("tmux paste-buffer -dpr"))
        assertTrue(command.contains("-t '=codex-main:'"))
        assertTrue(command.indexOf("sleep") in (command.indexOf("paste-buffer") + 1) until command.indexOf("send-keys"))
        assertFalse(command.contains("prompt text"))
        assertFalse(command.contains(" -S"))
    }

    @Test
    fun focusedActionsContainOnlyAllowlistedKeys() {
        assertEquals(
            "tmux send-keys -t '=work:' Enter",
            TmuxCommands.action("work", TmuxAction.ENTER),
        )
        assertEquals(
            "tmux send-keys -t '=work:' C-c",
            TmuxCommands.action("work", TmuxAction.INTERRUPT),
        )
        assertEquals(
            "tmux send-keys -t '=work:' Escape",
            TmuxCommands.action("work", TmuxAction.ESCAPE),
        )
    }

    @Test
    fun modelPickerSelectionUsesOnlyAValidatedNumberKey() {
        assertEquals(
            "tmux send-keys -t '=work:' 3",
            TmuxCommands.modelPickerOption("work", 3),
        )
        assertFalse(runCatching { TmuxCommands.modelPickerOption("work", 0) }.isSuccess)
        assertFalse(runCatching { TmuxCommands.modelPickerOption("work", 10) }.isSuccess)
    }

    @Test
    fun streamUsesOneFixedFramedCaptureLoop() {
        val command = TmuxCommands.stream("work")

        assertTrue(command.contains("while tmux has-session -t '=work'"))
        assertTrue(command.contains("tmux capture-pane -p -J -S -5000 -t '=work:'"))
        assertTrue(command.contains("tail -c 524288"))
        assertTrue(command.contains("mp_current=\$(cksum"))
        assertTrue(command.contains("mp_previous=\$mp_current"))
        assertTrue(command.contains(TmuxCommands.SNAPSHOT_PREFIX))
        assertTrue(command.contains("sleep 1"))
    }

    @Test
    fun streamPacksTheSnapshotWhenGzipAndBase64ExistAndKeepsHexOtherwise() {
        val command = TmuxCommands.stream("work")

        assertTrue(command.contains("command -v gzip >/dev/null 2>&1 && command -v base64 >/dev/null 2>&1"))
        assertTrue(command.contains("then mp_packed=1; else mp_packed=0; fi"))
        assertTrue(command.contains("if [ \"\$mp_packed\" = 1 ]; then printf '${TmuxCommands.SNAPSHOT_PACKED_MARKER}'; "))
        assertTrue(command.contains("gzip -c < \"\$mp_snapshot\" | base64 | tr -d '\\n'"))
        assertTrue(command.contains("else od -An -v -tx1 < \"\$mp_snapshot\" | tr -d ' \\n'; fi"))
    }

    @Test
    fun streamScriptQuotesTheSessionNameAndHoldsNoOtherUserContent() {
        val hostile = "a'; touch /bad; '"
        val command = TmuxCommands.stream(hostile)

        assertTrue(command.contains("-t ${TmuxParser.shellQuote("=$hostile")} "))
        assertTrue(command.contains("-t ${TmuxParser.shellQuote("=$hostile:")} "))
        val unquoted = command
            .replace(TmuxParser.shellQuote("=$hostile:"), "")
            .replace(TmuxParser.shellQuote("=$hostile"), "")
            .replace(TmuxParser.shellQuote(hostile), "")
        assertFalse(unquoted.contains("touch /bad"))
        assertEquals(
            TmuxCommands.stream("qxfirstqx").replace("qxfirstqx", "@"),
            TmuxCommands.stream("qxsecondqx").replace("qxsecondqx", "@"),
        )
    }

    @Test
    fun claudeSessionCreationQuotesWorkspaceAndSession() {
        val command = TmuxCommands.createClaudeSession("claude-work", "/tmp/a'; touch /bad")

        assertTrue(command.contains("-s 'claude-work'"))
        assertTrue(command.contains("-c '/tmp/a'\"'\"'; touch /bad'"))
        assertTrue(command.contains("exec claude --dangerously-skip-permissions"))
        assertTrue(command.contains(TmuxCommands.CREATED_PREFIX))
    }

    @Test
    fun fullShellSessionStartsWithoutAnAgentCommand() {
        val command = TmuxCommands.createShellSession("shell-work", "/srv/work")

        assertTrue(command.contains("tmux new-session -d -s 'shell-work' -c '/srv/work'"))
        assertFalse(command.contains("claude"))
    }

    @Test
    fun codexSessionQuotesTheFolderAndCarriesTheCodexFlags() {
        val command = TmuxCommands.createAgentSession(
            "codex-api",
            "/srv/a'; touch /bad",
            AgentHarness.CODEX,
        )

        assertTrue(command.contains("-s 'codex-api'"))
        assertTrue(command.contains("-c '/srv/a'\"'\"'; touch /bad'"))
        assertTrue(command.contains("command -v codex"))
        assertTrue(command.contains("--dangerously-bypass-approvals-and-sandbox"))
    }

    @Test
    fun plusClaudeStartsALiveChatLikeDesktopCtrlT() {
        val sid = "0b6d2c1e-4f3a-4b2c-9d8e-1a2b3c4d5e6f"
        val command = TmuxCommands.createLiveChat("live-api-x1", "/srv/api", sid)

        assertTrue(command.contains("-s 'live-api-x1'"))
        assertTrue(command.contains("command -v claude"))
        assertTrue(command.contains("reader.py host enable claude $sid --tmux live-api-x1 --cwd /srv/api --permission-mode bypassPermissions"))
        assertTrue(command.contains("reader.py host start claude $sid"))
        assertTrue(command.contains("exec sleep infinity"))
        assertFalse(command.contains("exec claude"))
        assertTrue(command.contains("printf '${TmuxCommands.CREATED_PREFIX}%s\\n' 'live-api-x1'"))
    }

    @Test
    fun liveChatRefusesAnUnsafeFolder() {
        assertThrows(IllegalArgumentException::class.java) {
            TmuxCommands.createLiveChat("live-a", "/srv/a'; touch /x", "0b6d2c1e-4f3a-4b2c-9d8e-1a2b3c4d5e6f")
        }
    }

    @Test
    fun liveChatRefusesAnUnsafeSessionId() {
        assertThrows(IllegalArgumentException::class.java) {
            TmuxCommands.createLiveChat("live-api-x1", "/srv/api", "x; reboot")
        }
    }

    @Test
    fun piSessionStartsPiWithoutExtraFlags() {
        val command = TmuxCommands.createAgentSession("pi-api", "/srv/api", AgentHarness.PI)

        assertTrue(command.contains("command -v pi"))
        assertTrue(command.contains("exec pi\""))
        assertFalse(command.contains("--dangerously"))
    }

    @Test
    fun directoryListingQuotesThePathAndMarksGitFolders() {
        val command = TmuxCommands.listDirectories("/srv/a'; reboot")

        assertTrue(command.contains("/srv/a'\"'\"'; reboot"))
        assertTrue(command.contains("\$mp_entry/.git"))
        assertTrue(command.contains("@root"))
        assertTrue(command.contains("printf '%s\\t%s\\n'"))
    }

    @Test
    fun homeDirectoryListingPrefersTheProjectsFolder() {
        val command = TmuxCommands.listDirectories()

        assertTrue(command.contains("mp_home/projects"))
        assertTrue(command.contains("@root"))
        assertFalse(command.contains("cd 'null'"))
    }

    @Test
    fun dissolveTargetsExactlyOneQuotedSessionAndToleratesAnAbsentOne() {
        assertEquals(
            "if tmux has-session -t '=work' 2>/dev/null; then tmux kill-session -t '=work'; fi",
            TmuxCommands.dissolveSession("work"),
        )
        assertEquals(
            "if tmux has-session -t '=a'\"'\"'; reboot' 2>/dev/null; then " +
                "tmux kill-session -t '=a'\"'\"'; reboot'; fi",
            TmuxCommands.dissolveSession("a'; reboot"),
        )
    }

    @Test
    fun renameChangesTheVisibleWindowButKeepsTheStableSessionId() {
        assertEquals(
            "tmux rename-window -t '=work:' 'New name'",
            TmuxCommands.renameWindow("work", "New name"),
        )
        assertEquals(
            "tmux rename-window -t '=a'\"'\"'; reboot:' 'It'\"'\"'s safe'",
            TmuxCommands.renameWindow("a'; reboot", "It's safe"),
        )
    }

    @Test
    fun renameUsesOneCommandAndTellsOnlyRealAgents() {
        assertEquals(TmuxCommands.renameWindow("live-b4", "Big Boss Man"), TmuxCommands.rename("live-b4", "Big Boss Man", tellAgent = false))
        assertEquals(
            "${TmuxCommands.renameWindow("work", "New name")} && ${TmuxCommands.pastePrompt("work")}",
            TmuxCommands.rename("work", "New name", tellAgent = true),
        )
    }

    @Test
    fun attachTargetsTheExactQuotedSession() {
        assertEquals(
            "exec tmux attach-session -f ignore-size -t '=a'\"'\"'; reboot'",
            TmuxCommands.attach("a'; reboot"),
        )
    }

    @Test
    fun etaReaderQuotesTheSessionAndCannotFailTheCommand() {
        val command = TmuxCommands.etaRowsCommand("claude-a'; rm -rf /")

        assertTrue(command.contains("eta-open.json"))
        assertTrue(command.contains("MULTIPROMPT_STATE_DIR"))
        assertTrue(command.contains(TmuxParser.shellQuote("claude-a'; rm -rf /")))
        assertTrue(command.endsWith("2>/dev/null || true"))
    }

    @Test
    fun listAndStreamCommandsCarryTheEstimateLine() {
        assertTrue(TmuxParser.command().contains(TmuxCommands.ETA_PREFIX))
        assertTrue(TmuxParser.command().contains("eta-open.json"))

        val stream = TmuxCommands.stream("work")
        assertTrue(stream.contains(TmuxCommands.ETA_PREFIX))
        assertTrue(stream.contains("eta-open.json"))
    }

    @Test
    fun hostedStreamWaitsQuietlyWhenTheChatHasNoTerminal() {
        val hosted = TmuxCommands.stream("live-desk", hosted = true)

        assertTrue(hosted.contains("while true; do sleep 30"))
        assertTrue(hosted.contains(TmuxCommands.SNAPSHOT_PREFIX))
        assertFalse(hosted.contains("tmux session is no longer available"))
        assertTrue(TmuxCommands.stream("live-desk").contains("tmux session is no longer available"))
    }

    @Test
    fun theStreamCarriesTheAlternateScreenMarker() {
        val stream = TmuxCommands.stream("work")

        assertTrue(stream.contains("printf '${TmuxCommands.ALT_PREFIX}%s\\n'"))
        assertTrue(stream.contains("#{alternate_on}"))
    }

    @Test
    fun resurrectUsesAFreshNameAndKeepsTheArchivedLabel() {
        val command = TmuxCommands.resurrectSession(
            sessionName = "hypertasks",
            displayName = "hypertasks",
            workingDirectory = "/srv/a'; touch /bad",
            resumeCommand = "claude --resume abc",
        )

        assertTrue(command.contains("if tmux has-session -t '=hypertasks-r"))
        assertTrue(command.contains("tmux new-session -d -s 'hypertasks-r"))
        assertFalse(command.contains("-s 'hypertasks'"))
        assertTrue(command.contains("-c '/srv/a'\"'\"'; touch /bad'"))
        assertTrue(command.contains("send-keys -t '=hypertasks-r"))
        assertTrue(command.contains("'claude --resume abc'"))
        assertTrue(command.contains("rename-window -t '=hypertasks-r"))
    }

    @Test
    fun freshSessionNameNeverReusesTheArchivedName() {
        assertEquals("hypertasks", TmuxCommands.freshSessionName("hypertasks").substringBefore("-r"))
        assertFalse("hypertasks" == TmuxCommands.freshSessionName("hypertasks"))
    }

    @Test
    fun latestClaudeSessionReadsOnlyTheProjectsFolder() {
        val command = TmuxCommands.latestClaudeSessionId("/srv/a'; reboot")

        assertTrue(command.contains("'/srv/a'\"'\"'; reboot'"))
        assertTrue(command.contains(".claude/projects/"))
        assertTrue(command.contains("jsonl"))
        assertTrue(command.contains("sed 's/[^A-Za-z0-9]/-/g'"))
    }

    @Test
    fun restoringALiveChatReusesItsSavedFolderInsteadOfTheStartFolder() {
        val sid = "0b6d2c1e-4f3a-4b2c-9d8e-1a2b3c4d5e6f"
        val command = TmuxCommands.resurrectLiveChat("live-api-x1", sid)

        assertTrue(command.contains("host enable \$mp_p $sid --tmux live-api-x1-r"))
        assertTrue(command.contains("host start \$mp_p $sid"))
        assertTrue(command.indexOf("mp_p=claude;") in 0 until command.indexOf("host stop \$mp_p $sid"))
        assertTrue(command.indexOf("host stop \$mp_p $sid") in 0 until command.indexOf("host enable \$mp_p $sid"))
        assertTrue(command.contains("tmux new-session -d -s 'live-api-x1-r"))
        assertFalse(command.contains("--cwd"))
        assertFalse(command.contains(" -c "))
        assertFalse(command.contains("claude --resume"))
    }

    @Test
    fun restoringALiveChatRejectsAnUnsafeChatId() {
        assertThrows(IllegalArgumentException::class.java) {
            TmuxCommands.resurrectLiveChat("live-api-x1", "x; reboot")
        }
    }

    @Test
    fun restoringALiveChatKeepsItsOriginalAssistant() {
        val sid = "0b6d2c1e-4f3a-4b2c-9d8e-1a2b3c4d5e6f"
        assertEquals("claude", pickedAssistant(sid, null))
        assertEquals("claude", pickedAssistant(sid, "claude"))
        assertEquals("codex", pickedAssistant(sid, "codex"))
        assertEquals("pi", pickedAssistant(sid, "pi"))
        assertEquals("hax", pickedAssistant(sid, "hax"))
        assertEquals("cursor", pickedAssistant(sid, "cursor"))
    }

    @Test
    fun theChatListReadsTheProviderTheHostRegistryKeepsForEachChat() {
        assumeTrue(runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        val home = Files.createTempDirectory("mp-home").toFile()
        try {
            val folder = File(home, ".local/state/multiprompt/agent-host").apply { mkdirs() }
            File(folder, "a.config.json").writeText("""{"enabled":true,"provider":"codex","sid":"s1","tmux":"live-one"}""")
            File(folder, "b.config.json").writeText("""{"enabled":true,"provider":"pi","sid":"s2","tmux":"live-two"}""")
            File(folder, "c.config.json").writeText("""{"enabled":false,"provider":"hax","sid":"s3","tmux":"live-off"}""")
            File(folder, "d.config.json").writeText("not json")
            File(folder, "e.config.json").writeText("""{"enabled":true,"provider":"claude","sid":"s4"}""")
            val process = ProcessBuilder("sh", "-c", TmuxCommands.etaRowsCommand())
                .apply { environment()["HOME"] = home.path; environment().remove("MULTIPROMPT_STATE_DIR") }
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            val providers = TmuxText.parseHostProviders(output)
            assertEquals(mapOf("live-one" to "codex", "live-two" to "pi"), providers)
        } finally {
            home.deleteRecursively()
        }
    }

    private fun pickedAssistant(sid: String, saved: String?): String {
        val home = Files.createTempDirectory("mp-home").toFile()
        try {
            val folder = File(home, ".local/state/multiprompt/agent-host").apply { mkdirs() }
            if (saved != null) {
                val key = MessageDigest.getInstance("SHA-256").digest("$saved:$sid".toByteArray())
                    .joinToString("") { "%02x".format(it) }.take(32)
                File(folder, "$key.config.json").writeText("{}")
            }
            val process = ProcessBuilder("sh", "-c", TmuxCommands.liveChatProvider(sid) + "; printf '%s' \"\$mp_p\"")
                .apply { environment()["HOME"] = home.path }
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            return output
        } finally {
            home.deleteRecursively()
        }
    }
}
