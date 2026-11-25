package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal object LongChatSnapshot {
    const val SID = "11111111-2222-3333-4444-555555555555"
    const val PROBE = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    const val SITE = "/home/valentin/projects/long-chat"
    private const val INODE = 4242424L
    private const val MTIME = 1_700_800_000_000L
    private const val START = 1_700_000_000L
    private val NEWLINE = byteArrayOf(10)
    private val budgets = HashMap<Int, Int>()

    val identity = ChatHistoryProtocol.Identity(AgentKind.CLAUDE, "", SITE, SID)

    data class Built(val reply: String, val bytes: ByteArray, val messages: Int, val size: Int)

    fun newline(): ByteArray = NEWLINE

    fun built(id: Long, targetBytes: Int, extraMessages: Int = 0): Built =
        build(id, budgetFor(id, targetBytes), extraMessages)

    @Synchronized
    private fun budgetFor(id: Long, targetBytes: Int): Int = budgets.getOrPut(targetBytes) { calibrate(id, targetBytes) }

    private fun calibrate(id: Long, targetBytes: Int): Int {
        var budget = targetBytes
        repeat(12) {
            val size = build(id, budget, 0).size
            if (kotlin.math.abs(size - targetBytes) <= targetBytes / 200) return budget
            budget = (budget.toDouble() * targetBytes / size).toInt().coerceAtLeast(1024)
        }
        return budget
    }

    private fun build(id: Long, targetBytes: Int, extraMessages: Int): Built {
        val rows = ArrayList<String>()
        var offset = 0L
        var used = header(id, 0L).toByteArray(Charsets.UTF_8).size + TranscriptProtocol.END.length + 2
        var index = 0
        while (used < targetBytes) {
            val line = row(offset, index, role(index))
            rows.add(line)
            used += line.toByteArray(Charsets.UTF_8).size + 1
            offset = offset + body(index, role(index)).toByteArray(Charsets.UTF_8).size
            index++
        }
        val baseMessages = index
        repeat(extraMessages) { extra ->
            val role = if (extra % 2 == 0) "user" else "assistant"
            val line = row(offset, index, role)
            rows.add(line)
            offset = offset + body(index, role).toByteArray(Charsets.UTF_8).size
            index++
        }
        rows.add(status(offset))
        val wire = (listOf(header(id, offset)) + rows + listOf(TranscriptProtocol.END)).joinToString("\n") + "\n"
        val reply = JSONObject().put("id", id).put("wire", wire).toString()
        val bytes = reply.toByteArray(Charsets.UTF_8)
        return Built(reply, bytes, baseMessages, bytes.size)
    }

    private fun header(id: Long, end: Long): String =
        "@mp\t$id\thistory\tok\t$SID\t0\t$end\t$INODE\t0\tclaude\t-\t$MTIME\t$PROBE\ttail\t0"

    private fun role(index: Int): String = when {
        index % 2 == 0 -> "user"
        index % 6 == 5 -> "tool"
        else -> "assistant"
    }

    private fun stamp(index: Int): String = Instant.ofEpochSecond(START + index * 60L).toString()

    private fun body(index: Int, role: String): String = when (role) {
        "user" -> "Step $index: read the newest snapshot and report where the phone spends its time."
        "tool" -> toolText()
        else -> buildString {
            append("Step $index is done.\n\nThe reader keeps its place while the tail grows.\n\n```kotlin\n")
            repeat(6) { line -> append("    val value$line = load($index, $line)\n") }
            append("```\n\nThen it writes the numbers to the log and compares them with the base revision.\n\n")
            append("- check the chat list\n- keep the scroll position\n- report the stage that dominates\n")
        }
    }

    private fun toolText(): String = "Bash git status --short\nworking tree clean\n".repeat(3) +
        "Read app/src/main/java/dev/multiprompt/companion/reader/ChatHistory.kt\n"

    private fun row(offset: Long, index: Int, role: String): String {
        val text = body(index, role)
        val row = JSONObject()
            .put("role", role)
            .put("text", text)
            .put("offset", offset)
            .put("end", offset + text.toByteArray(Charsets.UTF_8).size)
            .put("timestamp", stamp(index))
        if (index % 11 == 4) {
            row.put("id", "").put("read_id", "event:$PROBE").put("segment", index.toString())
        } else {
            row.put("id", "message-$index")
        }
        if (role == "user" && index % 26 == 0) row.put("queued", true)
        return row.toString()
    }

    private fun status(end: Long): String = JSONObject()
        .put("role", "status")
        .put("deliveries", JSONArray(listOf(JSONObject().put("id", "message-0").put("state", "delivered"))))
        .put("held", JSONArray(listOf(JSONObject()
            .put("text", "Queued follow-up")
            .put("queued_at", MTIME)
            .put("id", "held-1")
            .put("state", "queued")
            .put("sid", SID)
            .put("inode", INODE)
            .put("offset", end))))
        .toString()
}
