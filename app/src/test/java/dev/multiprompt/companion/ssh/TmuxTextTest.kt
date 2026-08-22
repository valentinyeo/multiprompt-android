package dev.multiprompt.companion.ssh

import dev.multiprompt.companion.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxTextTest {
    @Test
    fun removesDesktopMarginsFromEveryCapturedLine() {
        val captured = "  first line\n      wrapped line\n\n    final line"

        assertEquals(
            "first line\nwrapped line\n\nfinal line",
            TmuxText.leftAligned(captured),
        )
    }

    @Test
    fun decodesSnapshotHex() {
        assertEquals("hello\nworld", TmuxText.decodeHex("68656c6c6f0a776f726c64"))
    }

    @Test
    fun removesClaudeComposerAndFooterFromMobileTranscript() {
        val captured = """
            Finished the implementation.
            ______________________________
            __________
            ❯ test
            ______________________________
            Opus 5.4 medium | project
            bypass permissions on
        """.trimIndent()

        assertEquals(
            "Finished the implementation.",
            TmuxText.withoutActiveComposer(captured),
        )
    }

    @Test
    fun removesCodexComposerAndFooterFromMobileTranscript() {
        val captured = """
            Completed the requested changes.

            › Improve documentation in @filename

            gpt-5.6-sol high fast · project
        """.trimIndent()

        assertEquals(
            "Completed the requested changes.",
            TmuxText.withoutActiveComposer(captured),
        )
    }

    @Test
    fun leavesOrdinaryShellOutputUntouched() {
        val captured = "build complete\nuser@host:${'$'}"

        assertEquals(captured, TmuxText.withoutActiveComposer(captured))
    }

    @Test
    fun detectsClaudeAndCodexIdleComposers() {
        assertTrue(TmuxText.isWaitingForInput("Finished the task.\n❯"))
        assertTrue(TmuxText.isWaitingForInput("Completed the change.\n› Ask Codex"))
        assertTrue(TmuxText.isWaitingForInput("Press up to edit queued messages"))
    }

    @Test
    fun activeOutputDoesNotNeedInput() {
        assertFalse(TmuxText.isWaitingForInput("Building the APK…\nRunning tests"))
    }

    @Test
    fun newestCodexCompletionBeatsAnOlderPromptInTheCaptureTail() {
        assertTrue(TmuxText.isWaitingForInput("› Previous prompt\nWorked for 17m 43s"))
        assertFalse(TmuxText.isWaitingForInput("› Previous prompt\n• Working on the next task"))
    }

    @Test
    fun oldPromptFollowedByOrdinaryAgentOutputIsStillWorking() {
        assertFalse(
            TmuxText.isWaitingForInput(
                "› Previous prompt\nI am still applying the requested changes.\nReviewing the next file",
            ),
        )
    }

    @Test
    fun completionMustBeTheLatestMeaningfulTerminalState() {
        assertFalse(
            TmuxText.isWaitingForInput(
                "› Previous prompt\nWorked for 17m 43s\nContinuing with another change",
            ),
        )
        assertTrue(
            TmuxText.isWaitingForInput(
                "Completed the change.\nWorked for 17m 43s\ngpt-5.6-sol high fast · project",
            ),
        )
    }

    @Test
    fun activityAfterPromptVetoesAStaleCompletionMarker() {
        assertFalse(
            TmuxText.isWaitingForInput(
                "› Previous prompt\n• Working (1m 17s · esc to interrupt)\nWorked for 17m 43s",
            ),
        )
    }

    @Test
    fun detectsModelAndEffortFromAgentFooter() {
        assertEquals(
            TmuxText.RuntimeDetails("gpt-5.6-sol", "high"),
            TmuxText.runtimeDetails("gpt-5.6-sol high fast · project"),
        )
        assertEquals(
            TmuxText.RuntimeDetails("Opus 5.4", "medium"),
            TmuxText.runtimeDetails("Opus 5.4 medium | project"),
        )
        assertEquals(
            TmuxText.RuntimeDetails("Opus 5", "medium"),
            TmuxText.runtimeDetails("Opus 5 \u26a1medium | multiprompt-android | 9% | 87.2kin"),
        )
        assertEquals(
            TmuxText.RuntimeDetails("gpt-5.6-terra"),
            TmuxText.runtimeDetails("Current model: gpt-5.6-terra"),
        )
        assertEquals(
            TmuxText.RuntimeDetails("Claude Opus 4.7"),
            TmuxText.runtimeDetails("Model: Claude Opus 4.7"),
        )
        assertEquals(
            TmuxText.RuntimeDetails("Fable"),
            TmuxText.runtimeDetails("Fable"),
        )
    }

    @Test
    fun ignoresModelNamesQuotedInTheTranscriptAboveTheFooter() {
        val captured = buildString {
            appendLine("Run this one on gpt-5.6-luna max instead.")
            repeat(20) { appendLine("more transcript output") }
            appendLine("Opus 5 medium | multiprompt-android")
        }

        assertEquals(
            TmuxText.RuntimeDetails("Opus 5", "medium"),
            TmuxText.runtimeDetails(captured),
        )
    }

    @Test
    fun aBareModelAliasInTheTranscriptDoesNotOverrideTheFooter() {
        val captured = buildString {
            appendLine("Fable")
            repeat(20) { appendLine("more transcript output") }
            appendLine("Opus 5 medium | multiprompt-android")
        }

        assertEquals(
            TmuxText.RuntimeDetails("Opus 5", "medium"),
            TmuxText.runtimeDetails(captured),
        )
    }

    @Test
    fun readsNumberedOptionsFromTheCodexModelPicker() {
        assertEquals(
            listOf(
                TmuxText.ModelPickerOption(1, "gpt-5.4", current = true),
                TmuxText.ModelPickerOption(2, "gpt-5.3-codex"),
            ),
            TmuxText.modelPickerOptions(
                """
                Select Model and Effort
                › 1. gpt-5.4 (current)
                  2. gpt-5.3-codex
                """.trimIndent(),
            ),
        )
        assertTrue(TmuxText.modelPickerOptions("› 1. not a picker").isEmpty())
    }

    @Test
    fun rejoinsRowsTheTerminalHardWrapped() {
        val text = TmuxText.readerBlocks(
            """
            Add a genuinely new agent when a domain is unowned and its backlog is
            deep. If you want one anyway, tell me the domain.
            - a bullet that must stay on its own row and is long enough to fill it
            still part of the bullet
            """.trimIndent(),
            AgentKind.CLAUDE,
        ).single().text

        assertTrue(
            text.contains("and its backlog is deep. If you want one anyway"),
        )
        // A row the agent did not fill ends the paragraph.
        assertTrue(text.contains("tell me the domain.\n"))
        assertTrue(text.contains("\n- a bullet"))
        assertTrue(text.contains("fill it still part of the bullet"))
    }

    @Test
    fun toolOutputStaysWithItsActivityMarkerForClaudeToo() {
        val blocks = TmuxText.readerBlocks(
            """
            Now removing the activity toggles:

            • Bash(python3 - <<'PY')
            p='app/src/main/java/Foo.kt'
            s=open(p).read()

            Done, the toggles are gone.
            """.trimIndent(),
            AgentKind.CLAUDE,
        )

        assertEquals(
            listOf(
                TmuxText.ReaderBlockKind.PROSE,
                TmuxText.ReaderBlockKind.PROGRESS,
                TmuxText.ReaderBlockKind.PROSE,
            ),
            blocks.map { it.kind },
        )
    }

    @Test
    fun aPromptRowBreakingMidSentenceKeepsTheBubble() {
        val blocks = TmuxText.readerBlocks(
            """
            › Also, wanted to ask how many different flows do we have, and how
            would you say is the coverage of the app? Like, are we really
            covering functions on the board?

            Twelve flows cover the board.
            """.trimIndent(),
            AgentKind.CODEX,
        )

        assertEquals(TmuxText.ReaderBlockKind.USER_PROMPT, blocks.first().kind)
        assertTrue(blocks.first().text.endsWith("covering functions on the board?"))
        assertEquals(
            listOf(TmuxText.ReaderBlockKind.USER_PROMPT, TmuxText.ReaderBlockKind.PROSE),
            blocks.map { it.kind },
        )
    }

    @Test
    fun aCodexReplyBulletIsNotActivity() {
        val blocks = TmuxText.readerBlocks(
            """
            • Ran git status --short
            • Listing all flow titles · 2s
            • Yes, this session is working and I can reach the workspace.
            """.trimIndent(),
            AgentKind.CODEX,
        )

        assertEquals(
            listOf(TmuxText.ReaderBlockKind.PROGRESS, TmuxText.ReaderBlockKind.PROSE),
            blocks.map { it.kind },
        )
        assertTrue(blocks.last().text.startsWith("• Yes, this session is working"))
    }

    @Test
    fun aClaudeReplyBulletAndItsLinkRenderAsProse() {
        val blocks = TmuxText.readerBlocks(
            """
            ● Emergency ticket filed and handed to HT Desktop Developer:
              https://app.hypertask.ai/detail/project-15/5618
            """.trimIndent(),
            AgentKind.CLAUDE,
        )

        assertEquals(TmuxText.ReaderBlockKind.PROSE, blocks.single().kind)
        assertTrue(blocks.single().text.contains("https://app.hypertask.ai/detail/project-15/5618"))
    }

    @Test
    fun spinnerStatusRowsRenderAsProgress() {
        assertEquals(
            TmuxText.ReaderBlockKind.PROGRESS,
            TmuxText.readerBlocks("✳ Cooked for 1m 39s", AgentKind.CLAUDE).single().kind,
        )
        assertEquals(
            TmuxText.ReaderBlockKind.PROGRESS,
            TmuxText.readerBlocks("✢ Tempering… (2m 11s · ↓ 4.9k tokens)", AgentKind.CLAUDE).single().kind,
        )
    }

    @Test
    fun detectsLinksInReaderBlockText() {
        assertTrue(TmuxText.containsLink("Result: https://example.com/ticket"))
        assertTrue(TmuxText.containsLink("Open http://example.com locally"))
        assertFalse(TmuxText.containsLink("No link in this reply"))
    }

    @Test
    fun growsATranscriptFromAScrollingAgentScreen() {
        val lines = (1..30).map { "line $it" }
        var transcript = ""
        for (top in 0..20) {
            transcript = TmuxText.mergeSnapshot(
                transcript,
                lines.subList(top, top + 10).joinToString("\n"),
            )
        }

        assertEquals(lines, transcript.lines())
    }

    @Test
    fun aRedrawnScreenReplacesItselfRatherThanRepeating() {
        val body = (1..12).map { "answer line $it" }
        val first = (body + "Working 3s").joinToString("\n")
        val second = (body + "Working 9s").joinToString("\n")

        assertEquals(second, TmuxText.mergeSnapshot(first, second))
    }

    @Test
    fun theComposerBelowAStatusRowStillMeansWaiting() {
        val idle = """
            ● Pushed the fix.
            ─────────────────────────────────── MULTIPROMPT ANDROID ─
            ❯
            ──────────────────────────────────────────────────────────
            Opus 5 ⚡medium │ x
            ⏵⏵ bypass permissions on (shift+tab to cycle)
        """.trimIndent()

        assertTrue(TmuxText.isWaitingForInput(idle))
        assertFalse(
            TmuxText.isWaitingForInput(
                idle.replace("● Pushed the fix.", "✢ Tempering… (2m 11s · ↓ 4.9k tokens)"),
            ),
        )
    }

    @Test
    fun claudeUserTurnsRenderAsPrompts() {
        val blocks = TmuxText.readerBlocks("> fix the reader padding", AgentKind.CLAUDE)

        assertEquals(
            listOf(
                TmuxText.ReaderBlock(
                    TmuxText.ReaderBlockKind.USER_PROMPT,
                    "fix the reader padding",
                ),
            ),
            blocks,
        )
    }

    @Test
    fun heredocBodiesAreCodeNotConversation() {
        val blocks = TmuxText.readerBlocks(
            """
            p='app/src/main/java/Foo.kt'
            s=open(p).read()
            """.trimIndent(),
            AgentKind.CLAUDE,
        )

        assertEquals(listOf(TmuxText.ReaderBlockKind.CODE), blocks.map { it.kind })
    }

    @Test
    fun readerDropsPaneBannerCarryingASessionName() {
        val blocks = TmuxText.readerBlocks(
            """
            Mustering...
            ──────────────────────────────────── HT AGENT MANAGER ──
            Back to work.
            """.trimIndent(),
            AgentKind.CLAUDE,
        )

        assertEquals(
            listOf(
                TmuxText.ReaderBlock(TmuxText.ReaderBlockKind.PROSE, "Mustering..."),
                TmuxText.ReaderBlock(TmuxText.ReaderBlockKind.PROSE, "Back to work."),
            ),
            blocks,
        )
    }

    @Test
    fun keepsProseThatMerelyStartsAndEndsWithDashes() {
        val blocks = TmuxText.readerBlocks(
            "- the answer is that this line is real content and not a pane border -",
            AgentKind.CLAUDE,
        )

        assertEquals(1, blocks.size)
    }

    @Test
    fun readerDropsTerminalSeparatorsAndGroupsGenericCommandActivity() {
        val blocks = TmuxText.readerBlocks(
            """
            The useful answer.
            ──────────────────────────────
            • Ran git status --short
            command output that belongs to the tool call
            ──────────────────────────────
            Another useful answer.
            """.trimIndent(),
            AgentKind.CODEX,
        )

        assertEquals(
            listOf(
                TmuxText.ReaderBlock(TmuxText.ReaderBlockKind.PROSE, "The useful answer."),
                TmuxText.ReaderBlock(
                    TmuxText.ReaderBlockKind.PROGRESS,
                    "• Ran git status --short\ncommand output that belongs to the tool call",
                ),
                TmuxText.ReaderBlock(TmuxText.ReaderBlockKind.PROSE, "Another useful answer."),
            ),
            blocks,
        )
    }

    @Test
    fun readerBlocksSeparatePromptsCodeAndProgress() {
        val blocks = TmuxText.readerBlocks(
            """
            diff --git a/app.kt b/app.kt
            @@ -1 +1 @@
            -old()
            +new()
            ❯ Explain the change
            The change updates the reader.
            Working
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                TmuxText.ReaderBlock(
                    TmuxText.ReaderBlockKind.CODE,
                    """
                    diff --git a/app.kt b/app.kt
                    @@ -1 +1 @@
                    -old()
                    +new()
                """.trimIndent(),
                    language = "diff",
                    filePath = "app.kt",
                ),
                TmuxText.ReaderBlock(TmuxText.ReaderBlockKind.USER_PROMPT, "Explain the change"),
                TmuxText.ReaderBlock(TmuxText.ReaderBlockKind.PROSE, "The change updates the reader."),
                TmuxText.ReaderBlock(TmuxText.ReaderBlockKind.PROGRESS, "Working"),
            ),
            blocks,
        )
    }

    @Test
    fun readerBlocksRemoveFenceMarkers() {
        assertEquals(
            listOf(
                TmuxText.ReaderBlock(
                    TmuxText.ReaderBlockKind.CODE,
                    "val answer = 42",
                    language = "kotlin",
                ),
            ),
            TmuxText.readerBlocks("```kotlin\nval answer = 42\n```")
        )
    }

    @Test
    fun agentAdaptersRecognizePiPromptAndCodeMetadata() {
        val blocks = TmuxText.readerBlocks(
            """
            > Fix the parser
            ```kotlin
            val answer = 42
            ```
            """.trimIndent(),
            AgentKind.PI,
        )

        assertEquals(TmuxText.ReaderBlockKind.USER_PROMPT, blocks[0].kind)
        assertEquals("Fix the parser", blocks[0].text)
        assertEquals("kotlin", blocks[1].language)
    }

    @Test
    fun agentAdaptersKeepClaudeAndCodexPromptMarkers() {
        assertEquals(
            TmuxText.ReaderBlockKind.USER_PROMPT,
            TmuxText.readerBlocks("❯ Claude prompt", AgentKind.CLAUDE).single().kind,
        )
        assertEquals(
            TmuxText.ReaderBlockKind.USER_PROMPT,
            TmuxText.readerBlocks("› Codex prompt", AgentKind.CODEX).single().kind,
        )
    }

    @Test
    fun codexActivityAndNumberedDiffLinesStayOutOfProse() {
        val blocks = TmuxText.readerBlocks(
            """
            • Ran git diff -- src/App.tsx
            └ 29 +    assert.match(source)
            30 +    expect(description).toBeTruthy()

            The change is ready.
            """.trimIndent(),
            AgentKind.CODEX,
        )

        assertEquals(
            listOf(
                TmuxText.ReaderBlock(
                    TmuxText.ReaderBlockKind.PROGRESS,
                    """
                    • Ran git diff -- src/App.tsx
                    └ 29 +    assert.match(source)
                    30 +    expect(description).toBeTruthy()
                    """.trimIndent(),
                ),
                TmuxText.ReaderBlock(
                    TmuxText.ReaderBlockKind.PROSE,
                    "The change is ready.",
                ),
            ),
            blocks,
        )
    }

    @Test
    fun wrappedCodexPromptStaysInOneUserBubble() {
        val blocks = TmuxText.readerBlocks(
            """
            › https://screencast2.com/AhOPZ.png And on the inbox view, move these buttons for open,
            waiting, and archive to the top. Make the inbox a dropdown.
            """.trimIndent(),
            AgentKind.CODEX,
        )

        assertEquals(
            listOf(
                TmuxText.ReaderBlock(
                    TmuxText.ReaderBlockKind.USER_PROMPT,
                    "https://screencast2.com/AhOPZ.png And on the inbox view, move these buttons for open, " +
                        "waiting, and archive to the top. Make the inbox a dropdown.",
                ),
            ),
            blocks,
        )
    }

    @Test
    fun naturalLanguageForLineIsNotMisclassifiedAsCode() {
        assertEquals(
            TmuxText.ReaderBlockKind.PROSE,
            TmuxText.readerBlocks("for the inbox, make this a dropdown", AgentKind.CODEX).single().kind,
        )
    }
}
