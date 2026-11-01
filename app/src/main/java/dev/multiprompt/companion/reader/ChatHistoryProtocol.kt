package dev.multiprompt.companion.reader

import dev.multiprompt.companion.model.AgentKind
import org.json.JSONObject

object ChatHistoryProtocol {
    const val COMMAND = "exec python3 -u -c 'import base64,sys;exec(base64.b64decode(sys.stdin.buffer.readline()))' --serve"
    const val MAX_REPLY_BYTES = 64 * 1024 * 1024

    data class Identity(val agent: AgentKind, val tmux: String, val cwd: String = "", val sid: String = "")

    enum class QueueOperation(val wire: String) {
        ENQUEUE("enqueue"), REMOVE("remove"), DEQUEUE("dequeue"), POP_ALL("popAll"), REJECTED("rejected");
    }

    data class Settlement(val operation: QueueOperation, val content: String)

    data class Row(
        val role: String,
        val text: String,
        val id: String,
        val timestamp: String,
        val offset: Long,
        val end: Long,
        val queued: Boolean = false,
        val settlement: Settlement? = null,
        val questions: List<PendingQuestion> = emptyList(),
        val questionResult: String = "",
        val normalized: String = "",
    ) {
        fun json(): JSONObject = (if (normalized.isEmpty()) JSONObject() else JSONObject(normalized)).put("role", role).put("text", text).put("id", id)
            .put("timestamp", timestamp).put("offset", offset).put("end", end).put("queued", queued)
            .also { row ->
                if (questions.isNotEmpty()) row.put("question", JSONObject()
                    .put("provider", questions.first().provider)
                    .put("questions", org.json.JSONArray(questions.map { it.json() })))
                if (questionResult.isNotEmpty()) row.put("questionResult", questionResult)
            }
            .also { row -> settlement?.let { row.put("settlement", JSONObject().put("operation", it.operation.wire).put("content", it.content)) } }
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
        val deliveries: Map<String, QueuedMessages.Delivery> = emptyMap(),
        val held: List<QueuedMessages.SentPrompt> = emptyList(),
        val pendingQuestions: List<PendingQuestion> = emptyList(),
    )

    fun request(id: Long, identity: Identity, history: ChatHistory, before: Long = 0, deliveryIds: List<String> = emptyList(), held: Boolean = true): String {
        require(id > 0 && TranscriptProtocol.supports(identity.agent))
        return JSONObject().put("id", id).put("codex", TranscriptProtocol.wireAgent(identity.agent) == "codex")
            .put("sid", identity.sid).put("tmux", identity.tmux).put("cwd", identity.cwd).put("follow", identity.tmux.isNotEmpty())
            .put("questions", true).put("held", held).put("delivery_ids", org.json.JSONArray(deliveryIds)).put("source_offsets", true).put("no_jump", true).put("settlement", true)
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
        require(fields[9] == TranscriptProtocol.wireAgent(identity.agent) && fields[10] == identity.tmux.ifEmpty { "-" })
        if (identity.tmux.isEmpty() && identity.sid.isNotEmpty()) require(fields[4] == identity.sid)
        require(fields[8] in setOf("0", "1") && fields[13] in setOf("tail", "delta", "older"))
        require(fields[12].matches(Regex("[0-9a-f]{64}")))
        val base = fields[5].toLong()
        val end = fields[6].toLong()
        val oldest = fields[14].toLong()
        require(oldest in 0..base && end >= base && fields[7].toLong() > 0)
        val deliveries = mutableMapOf<String, QueuedMessages.Delivery>()
        val held = mutableListOf<QueuedMessages.SentPrompt>()
        var pendingQuestions = emptyList<PendingQuestion>()
        val rows = lines.subList(1, lines.lastIndex).mapNotNull { line ->
            val row = JSONObject(line)
            if (row.optString("role") == "status") {
                pendingQuestions = parseQuestionSnapshot(row.optJSONObject("question"), fields[9])
            }
            row.optJSONArray("held")?.let { values ->
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    held.add(QueuedMessages.SentPrompt(value.getString("text"), value.getLong("queued_at"),
                        messageId = value.getString("id"), delivery = QueuedMessages.Delivery.valueOf(value.getString("state").uppercase()),
                        sid = value.getString("sid"), inode = value.getLong("inode"), afterOffset = value.getLong("offset")))
                }
            }
            row.optJSONArray("deliveries")?.let { values ->
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    deliveries[value.getString("id")] = QueuedMessages.Delivery.valueOf(value.getString("state").uppercase())
                }
            }
            if (row.optString("role") !in setOf("user", "assistant")) null else parseRow(row).also {
                require(it.offset >= base && it.end <= end && it.end > it.offset)
            }
        }
        require(rows.zipWithNext().all { (a, b) -> a.offset <= b.offset })
        return Page(fields[4], base, end, fields[7].toLong(), fields[8] == "1", fields[11].toLong(),
            fields[12], fields[13], oldest, rows, deliveries, held, pendingQuestions)
    }

    private fun parseQuestionSnapshot(request: JSONObject?, agent: String): List<PendingQuestion> = runCatching {
        if (request == null) return@runCatching emptyList()
        val id = request.get("id") as? String ?: return@runCatching emptyList()
        val provider = (if (request.has("provider")) request.get("provider") as? String else "claude") ?: return@runCatching emptyList()
        if (id.isBlank() || provider != agent || provider !in setOf("claude", "codex", "pi")) return@runCatching emptyList()
        val questions = request.get("questions") as? org.json.JSONArray ?: return@runCatching emptyList()
        require(questions.length() > 0)
        (0 until questions.length()).map { index ->
            val value = questions.get(index) as? JSONObject ?: error("Invalid question")
            require((value.opt("question") as? String)?.isNotBlank() == true)
            require(!value.has("header") || value.opt("header") is String)
            for (key in listOf("multiSelect", "other", "isOther")) require(!value.has(key) || value.opt(key) is Boolean)
            val options = if (value.has("options")) value.get("options") as? org.json.JSONArray ?: error("Invalid options") else org.json.JSONArray()
            require(provider != "claude" || options.length() > 0)
            for (optionIndex in 0 until options.length()) {
                val option = options.get(optionIndex) as? JSONObject ?: error("Invalid option")
                require((option.opt("label") as? String)?.isNotBlank() == true)
                require(!option.has("description") || option.opt("description") is String)
            }
            PendingQuestion.parse(id, index, value, provider)
        }
    }.getOrDefault(emptyList())

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
        row.getLong("offset"), row.getLong("end"), row.optBoolean("queued"),
        row.optJSONObject("settlement")?.let { fact ->
            Settlement(QueueOperation.entries.first { it.wire == fact.getString("operation") }, fact.getString("content"))
        },
        row.optJSONObject("question")?.let { request ->
            val questions = request.getJSONArray("questions")
            (0 until questions.length()).map { index ->
                PendingQuestion.parse(row.getString("id"), index, questions.getJSONObject(index), request.getString("provider"))
            }
        }.orEmpty(), row.optString("questionResult"), JSONObject(row.toString()).also { metadata ->
            listOf("role", "text", "id", "timestamp", "offset", "end", "queued", "settlement", "question", "questionResult").forEach { metadata.remove(it) }
        }.let { if (it.length() == 0) "" else it.toString() },
    ).also {
        require(it.role in setOf("user", "assistant") && it.offset >= 0 && it.end > it.offset)
        require(if (it.settlement == null && it.questions.isEmpty() && it.questionResult.isEmpty()) it.text.isNotBlank() else it.role == "assistant" && it.text.isEmpty())
        require(!it.queued || it.role == "user" && it.settlement == null)
    }
}
