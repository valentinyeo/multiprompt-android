package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject

object ChatHistoryProtocol {
    const val COMMAND = "exec python3 -u -c 'import base64,sys;exec(base64.b64decode(sys.stdin.buffer.readline()))' --serve"
    const val MAX_REPLY_BYTES = 64 * 1024 * 1024

    data class Identity(val agent: AgentKind, val tmux: String, val cwd: String = "", val sid: String = "")

    data class Row(
        val role: String,
        val text: String,
        val id: String,
        val timestamp: String,
        val offset: Long,
        val end: Long,
    ) {
        fun json(): JSONObject = JSONObject().put("role", role).put("text", text).put("id", id)
            .put("timestamp", timestamp).put("offset", offset).put("end", end)
    }

    data class Page(
        val sid: String,
        val base: Long,
        val end: Long,
        val inode: Long,
        val more: Boolean,
        val mtime: Long,
        val probe: String,
        val mode: String,
        val oldest: Long,
        val rows: List<Row>,
    )

    fun request(id: Long, identity: Identity, history: ChatHistory, before: Long = 0): String {
        require(id > 0 && TranscriptProtocol.supports(identity.agent))
        return JSONObject().put("id", id).put("codex", identity.agent == AgentKind.CODEX)
            .put("sid", identity.sid).put("tmux", identity.tmux).put("cwd", identity.cwd)
            .put("source_offsets", true).put("no_jump", true)
            .put("cached_sid", history.sid).put("inode", history.inode).put("offset", history.offset)
            .put("start", history.start).put("before", before).put("mtime", history.mtime)
            .put("probe", history.probe).toString() + "\n"
    }

    fun parse(reply: String, id: Long, identity: Identity): Page? {
        require(reply.toByteArray(Charsets.UTF_8).size <= MAX_REPLY_BYTES)
        val envelope = JSONObject(reply)
        require(envelope.getLong("id") == id)
        val lines = envelope.getString("wire").trimEnd('\n').split('\n')
        val fields = lines.first().split('\t')
        require(fields.size >= 4 && fields.take(3) == listOf("@mp", id.toString(), "history"))
        require(lines.last() == TranscriptProtocol.END)
        if (fields[3] in setOf("missing", "error")) return null
        require(fields.size == 15 && fields[3] == "ok")
        require(fields[4].matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(fields[9] == identity.agent.name.lowercase() && fields[10] == identity.tmux.ifEmpty { "-" })
        if (identity.tmux.isEmpty() && identity.sid.isNotEmpty()) require(fields[4] == identity.sid)
        require(fields[8] in setOf("0", "1") && fields[13] in setOf("tail", "delta", "older"))
        require(fields[12].matches(Regex("[0-9a-f]{64}")))
        val base = fields[5].toLong()
        val end = fields[6].toLong()
        val oldest = fields[14].toLong()
        require(oldest in 0..base && end >= base && fields[7].toLong() > 0)
        val rows = lines.subList(1, lines.lastIndex).mapNotNull { line ->
            val row = JSONObject(line)
            if (row.optString("role") !in setOf("user", "assistant")) null else parseRow(row).also {
                require(it.offset >= base && it.end <= end && it.end > it.offset)
            }
        }
        require(rows.zipWithNext().all { (a, b) -> a.offset <= b.offset })
        return Page(fields[4], base, end, fields[7].toLong(), fields[8] == "1", fields[11].toLong(),
            fields[12], fields[13], oldest, rows)
    }

    class Framer {
        private val pending = java.io.ByteArrayOutputStream()

        fun feed(chunk: ByteArray): List<String> {
            val replies = ArrayList<String>()
            for (byte in chunk) {
                require(pending.size() < MAX_REPLY_BYTES)
                if (byte == '\n'.code.toByte()) {
                    replies.add(pending.toString(Charsets.UTF_8.name()))
                    pending.reset()
                } else {
                    pending.write(byte.toInt())
                }
            }
            return replies
        }

        fun hasPending(): Boolean = pending.size() > 0
    }

    internal fun parseRow(row: JSONObject): Row = Row(
        row.getString("role"), row.getString("text"), row.optString("id"), row.optString("timestamp"),
        row.getLong("offset"), row.getLong("end"),
    ).also {
        require(it.role in setOf("user", "assistant") && it.text.isNotBlank() && it.offset >= 0 && it.end > it.offset)
    }
}
