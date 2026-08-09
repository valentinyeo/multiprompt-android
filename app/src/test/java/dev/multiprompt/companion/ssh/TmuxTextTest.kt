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
                    """.trimIndent(),
                ),
                TmuxText.ReaderBlock(
                    TmuxText.ReaderBlockKind.CODE,
                    """
                    30 +    expect(description).toBeTruthy()
                    """.trimIndent(),
                    language = "diff",
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
                    "https://screencast2.com/AhOPZ.png And on the inbox view, move these buttons for open,\n" +
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
