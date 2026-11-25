package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import dev.multiprompt.companion.ssh.SshRepository
import dev.multiprompt.companion.ssh.TmuxCommands
import dev.multiprompt.companion.ssh.TmuxText
import dev.multiprompt.companion.ui.isWorking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.Base64
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class TmuxPaneFrameCostTest {
    private class Stage(val name: String, val thread: String, val source: String) {
        val nanos = ArrayList<Long>()
        val allocated = ArrayList<Long>()

        fun meanMs(): Double = if (nanos.isEmpty()) 0.0 else nanos.average() / 1_000_000.0

        fun p95Ms(): Double {
            if (nanos.isEmpty()) return 0.0
            val sorted = nanos.sorted()
            return sorted[((sorted.size - 1) * 95) / 100] / 1_000_000.0
        }

        fun meanKiB(): Double = if (allocated.isEmpty() || allocated.any { it < 0 }) -1.0 else allocated.average() / 1024.0
    }

    private class Timing(rows: List<Triple<String, String, String>>) {
        private val stages = rows.associate { it.first to Stage(it.first, it.second, it.third) }
        private val ordered = rows.map { it.first }

        fun stage(name: String): Stage = requireNotNull(stages[name]) { "unknown stage $name" }

        fun names(): List<String> = ordered
    }

    private fun measure(timing: Timing, name: String, block: () -> Unit) {
        val before = allocatedBytes()
        val start = System.nanoTime()
        block()
        val elapsed = System.nanoTime() - start
        val after = allocatedBytes()
        val stage = timing.stage(name)
        stage.nanos += elapsed
        stage.allocated += if (before < 0 || after < 0) -1L else (after - before).coerceAtLeast(0L)
    }

    private fun splitFrame(line: String): String {
        val pending = StringBuilder()
        pending.append(line)
        val end = pending.indexOf("\n")
        if (end < 0) return pending.toString()
        val frameLine = pending.substring(0, end)
        pending.delete(0, end + 1)
        return frameLine.removePrefix(TmuxCommands.SNAPSHOT_PREFIX)
    }

    private fun gunzip(compressed: ByteArray): String {
        val decoded = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                decoded.write(buffer, 0, read)
            }
        }
        return decoded.toByteArray().toString(Charsets.UTF_8)
    }

    private fun serverFrame(text: String): String {
        val gzipped = ByteArrayOutputStream()
        GZIPOutputStream(gzipped).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return TmuxCommands.SNAPSHOT_PREFIX + TmuxCommands.SNAPSHOT_PACKED_MARKER +
            Base64.getEncoder().encodeToString(gzipped.toByteArray()) + "\n"
    }

    private fun runFrame(
        timing: Timing,
        line: String,
        expected: String,
        state: MutableStateFlow<ReaderState>,
        clock: Long,
    ): Boolean {
        var payload = ""
        measure(timing, RECEIVE_SPLIT) { payload = splitFrame(line) }
        var compressed = ByteArray(0)
        measure(timing, BASE64) {
            compressed = Base64.getDecoder().decode(payload.removePrefix(TmuxCommands.SNAPSHOT_PACKED_MARKER))
        }
        var raw = ""
        measure(timing, GUNZIP) { raw = gunzip(compressed) }
        if (raw != expected) throw AssertionError("the decoded pane text differs from the sent pane")
        var aligned = ""
        measure(timing, LEFT_ALIGN) { aligned = TmuxText.leftAligned(raw) }
        var mobile = ""
        measure(timing, COMPOSER_TRIM) { mobile = TmuxText.withoutActiveComposer(aligned, AGENT) }
        var frame: SshRepository.StreamSnapshot? = null
        measure(timing, FRAME_DETAILS) {
            frame = SshRepository.StreamSnapshot(
                output = mobile,
                screenOutput = aligned,
                details = TmuxText.runtimeDetails(aligned),
                pickerOptions = TmuxText.modelPickerOptions(aligned),
                waitingForInput = TmuxText.isWaitingForInput(aligned, AGENT),
                alternateOn = false,
                confirmationVisible = TmuxText.isModelSwitchConfirmation(aligned),
                eta = null,
                hookState = null,
            )
        }
        val snapshot = requireNotNull(frame)
        val current = state.value
        var merged = ""
        var details = current.runtimeDetails
        measure(timing, MERGE) {
            merged = TmuxText.mergeSnapshot(current.output, snapshot.output)
            details = snapshot.details.takeIf { it.model != null }
                ?: TmuxText.runtimeDetails(merged).takeIf { it.model != null }
                ?: current.runtimeDetails
        }
        var emitted = false
        measure(timing, STATE_EMIT) {
            state.value = current.copy(
                output = merged,
                screenOutput = snapshot.screenOutput,
                runtimeDetails = details,
                modelPickerOptions = snapshot.pickerOptions,
                status = ReaderStatus.Live,
                lastFailure = null,
                lastUpdatedAtMillis = clock,
                waitingForInput = snapshot.waitingForInput,
                alternateOn = snapshot.alternateOn,
                eta = null,
                hookState = null,
            )
            emitted = state.value !== current
        }
        measure(timing, UI_WORKING_READ) { state.value.isWorking(AGENT) }
        return emitted
    }

    @Test
    fun reportsThePhoneCostOfOnePaneFrame() {
        val identicalPane = paneText()
        val identicalFrame = serverFrame(identicalPane)
        assertTrue(identicalPane.toByteArray(Charsets.UTF_8).size <= PANE_BYTES)
        assertTrue(identicalPane.lineSequence().count() >= PANE_LINES - 200)

        val state = MutableStateFlow(seedState())
        val identical = Timing(STAGES)
        var identicalEmits = 0
        repeat(FRAMES) { index ->
            if (runFrame(identical, identicalFrame, identicalPane, state, CLOCK + index * 1_000L)) identicalEmits++
        }

        val changed = Timing(STAGES)
        var changedEmits = 0
        repeat(FRAMES) { index ->
            val pane = paneText(changedRow = (index * 97) % PANE_LINES)
            val line = serverFrame(pane)
            if (runFrame(changed, line, pane, state, CLOCK + (FRAMES + index) * 1_000L)) changedEmits++
        }

        assertEquals(FRAMES, identicalEmits)
        assertEquals(FRAMES, changedEmits)
        assertTrue(state.value.output.isNotBlank())
        publish(report(identical, changed, identicalPane, identicalFrame.length, identicalEmits, changedEmits))
    }

    private fun report(
        identical: Timing,
        changed: Timing,
        pane: String,
        payloadChars: Int,
        identicalEmits: Int,
        changedEmits: Int,
    ): String = buildString {
        appendLine("### MUBO-453: phone cost of one tmux pane frame")
        appendLine()
        appendLine(
            "Synthetic pane: ${pane.lineSequence().count()} lines, ${pane.toByteArray(Charsets.UTF_8).size / 1024} KiB " +
                "of text with ANSI colour, encoded exactly like `TmuxCommands.stream` (gzip, base64, `gz:` marker, " +
                "$payloadChars chars on the wire). $FRAMES frames per run; the second run changes one line per frame. " +
                "Measured on `${Thread.currentThread().name}`.",
        )
        appendLine()
        appendLine("| stage | thread | source | identical mean ms | identical p95 ms | changed mean ms | changed p95 ms | KiB allocated per frame |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (name in identical.names()) {
            val a = identical.stage(name)
            val b = changed.stage(name)
            appendLine(
                "| ${a.name} | ${a.thread} | `${a.source}` | ${ms(a.meanMs())} | ${ms(a.p95Ms())} | " +
                    "${ms(b.meanMs())} | ${ms(b.p95Ms())} | ${kib(a.meanKiB())} |",
            )
        }
        appendLine()
        appendLine("- identical frames: $identicalEmits of $FRAMES emitted a new ReaderState and recomposed (`lastUpdatedAtMillis`, `SessionReaderConnection.kt:621`); changed frames: $changedEmits of $FRAMES.")
        appendLine("- the phone decodes and re-parses every frame it receives; the server is what skips an unchanged pane (`TmuxCommands.kt:415`).")
        appendLine("- the pane view is not the only reader: `reader.isWorking` parses `screenOutput` on Main even when the chat shows the structured history (`MultipromptApp.kt:3127`, `WorkingDots.kt:27`).")
    }

    private fun ms(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun kib(value: Double): String = if (value < 0) "n/a" else String.format(Locale.US, "%.0f", value)

    private fun publish(report: String) {
        println(report)
        val summary = System.getenv("GITHUB_STEP_SUMMARY")
        if (!summary.isNullOrBlank()) runCatching { java.io.File(summary).appendText(report + "\n") }
    }

    private fun seedState(): ReaderState = ReaderState(
        output = (0 until HISTORY_LINES).joinToString("\n") { rowText(it, false) },
        status = ReaderStatus.Live,
    )

    private fun paneText(changedRow: Int = -1): String {
        val rows = ArrayList<String>(PANE_LINES + 1)
        for (index in 0 until PANE_LINES) rows += rowText(index, index == changedRow)
        var bytes = rows.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
        while (rows.size > 1 && bytes > PANE_BYTES) {
            val dropped = rows.removeAt(0)
            bytes -= dropped.toByteArray(Charsets.UTF_8).size + 1
        }
        return rows.joinToString("\n")
    }

    private fun rowText(index: Int, changed: Boolean): String {
        val core = when (index % 8) {
            0 -> "\u001b[38;5;250m● Bash(./gradlew :app:testDebugUnitTest)\u001b[0m"
            1 -> "  \u001b[2m└\u001b[0m row $index of the captured pane, wrapped at the desktop width"
            2 -> "  \u001b[32m✓\u001b[0m TmuxText.kt:$index parsed, 12 ms"
            3 -> "  \u001b[38;5;244m│\u001b[0m wrapped prose line $index with ordinary words in it"
            4 -> "  \u001b[36m•\u001b[0m Reading file $index of 5000"
            5 -> "  \u001b[2m─────\u001b[0m row $index │ cost │ stage │ thread"
            6 -> "    \u001b[33m+ \u001b[0m diff hunk line $index with plus signs and indent"
            else -> ""
        }
        val suffix = if (changed) " \u001b[33m· changed\u001b[0m" else ""
        return (core + suffix + "  " + token(index)).padEnd(WIDTH)
    }

    private fun token(index: Int): String = java.lang.Long.toHexString(index * -7046029254386353131L)

    private fun allocatedBytes(): Long {
        val bean = allocationBean ?: return -1L
        return runCatching { bean.getCurrentThreadAllocatedBytes() }.getOrElse { -1L }
    }

    private companion object {
        const val PANE_LINES = 5000
        const val PANE_BYTES = 512 * 1024
        const val HISTORY_LINES = 3000
        const val WIDTH = 104
        const val FRAMES = 60
        const val CLOCK = 1_700_000_000_000L
        val AGENT = AgentKind.CLAUDE

        const val RECEIVE_SPLIT = "receive-split"
        const val BASE64 = "base64"
        const val GUNZIP = "gunzip"
        const val LEFT_ALIGN = "left-align"
        const val COMPOSER_TRIM = "composer-trim"
        const val FRAME_DETAILS = "frame-details"
        const val MERGE = "merge"
        const val STATE_EMIT = "state-emit"
        const val UI_WORKING_READ = "ui-working-read"

        val STAGES = listOf(
            Triple(RECEIVE_SPLIT, "Dispatchers.IO", "SshRepository.kt:227-258"),
            Triple(BASE64, "Dispatchers.IO", "TmuxText.kt:192-201"),
            Triple(GUNZIP, "Dispatchers.IO", "TmuxText.kt:203-219"),
            Triple(LEFT_ALIGN, "Dispatchers.IO", "SshRepository.kt:259"),
            Triple(COMPOSER_TRIM, "Dispatchers.IO", "TmuxText.kt:274"),
            Triple(FRAME_DETAILS, "Dispatchers.IO", "SshRepository.kt:264-277"),
            Triple(MERGE, "Dispatchers.IO", "SessionReaderConnection.kt:604-609"),
            Triple(STATE_EMIT, "Dispatchers.IO", "SessionReaderConnection.kt:610-631"),
            Triple(UI_WORKING_READ, "Main", "WorkingDots.kt:27"),
        )

        val allocationBean: com.sun.management.ThreadMXBean? = runCatching {
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            if (bean.isThreadAllocatedMemorySupported && !bean.isThreadAllocatedMemoryEnabled) {
                bean.isThreadAllocatedMemoryEnabled = true
            }
            bean.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
        }.getOrNull()
    }
}
