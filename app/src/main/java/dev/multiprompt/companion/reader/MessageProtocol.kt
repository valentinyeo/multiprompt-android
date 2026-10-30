package dev.multiprompt.companion.reader

import org.json.JSONArray
import org.json.JSONObject

object MessageProtocol {
    data class Scope(val provider: String, val sid: String, val tmux: String) {
        init {
            require(provider in setOf("claude", "codex"))
            require(sid.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            require(tmux.matches(Regex("[A-Za-z0-9_.:-]{1,80}")))
        }
    }

    enum class State { QUEUED, SENT, ACCEPTED, STARTED, FAILED, CANCELLED }
    data class Fact(val sid: String, val eventId: String, val inode: Long?, val offset: Long?, val end: Long?, val json: String)
    data class Row(
        val id: String, val provider: String, val sid: String, val text: String,
        val position: Long, val attempt: String, val state: State, val deadline: Double?,
        val receipt: String?, val epoch: Long, val created: Double,
    )

    sealed interface Item {
        data class Message(val row: Row) : Item
        data class Native(val fact: Fact) : Item
        data class AttemptReceipt(val fact: Fact) : Item
        data class Reset(val epoch: Long) : Item
    }
    data class SnapshotPage(val id: String, val epoch: Long, val seq: Long, val items: List<Item>, val next: Long?)
    data class Event(val seq: Long, val item: Item)
    data class Events(val epoch: Long, val head: Long, val events: List<Event>, val next: Long)
    data class Result(val epoch: Long, val seq: Long, val row: Row?)

    fun request(id: Long, scope: Scope, operation: String, arguments: JSONObject = JSONObject()): String {
        require(id > 0)
        require(operation in setOf("message_submit", "message_retry", "message_dismiss", "message_status", "message_snapshot", "message_events", "message_reset"))
        val request = JSONObject(arguments.toString())
        for (key in listOf("id", "op", "message_protocol", "provider", "sid", "tmux")) require(!request.has(key))
        return request.put("id", id).put("op", operation).put("message_protocol", 1)
            .put("provider", scope.provider).put("sid", scope.sid).put("tmux", scope.tmux).toString() + "\n"
    }

    private fun integral(value: Any?): Long {
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it >= 0) }
    }
    private fun number(value: Any?): Double {
        require(value is Number)
        return value.toDouble().also { require(it.isFinite()) }
    }
    private fun text(value: Any?): String = value as? String ?: error("Expected string")
    private fun optionalString(value: Any?): String? = if (value == JSONObject.NULL) null else text(value)
    private fun optionalNumber(value: Any?): Double? = if (value == JSONObject.NULL) null else number(value)
    private fun jsonObject(value: Any?): JSONObject = value as? JSONObject ?: error("Expected object")
    private fun array(value: Any?): JSONArray = value as? JSONArray ?: error("Expected array")

    private fun result(reply: String, id: Long): JSONObject {
        require(reply.toByteArray(Charsets.UTF_8).size <= ChatHistoryProtocol.MAX_REPLY_BYTES)
        val envelope = JSONObject(reply)
        require(integral(envelope.get("id")) == id && integral(envelope.get("message_protocol")) == 1L)
        return jsonObject(envelope.get("result"))
    }

    fun engine(reply: String, id: Long): Pair<Long, String> {
        val envelope = JSONObject(reply)
        require(integral(envelope.get("id")) == id)
        val info = jsonObject(envelope.get("engine_info"))
        require(integral(info.get("protocol")) == 1L)
        val generation = integral(info.get("generation"))
        val hash = text(info.get("sha256"))
        require(generation >= 21 && hash.matches(Regex("[a-f0-9]{64}")))
        val protocols = array(envelope.get("message_protocols"))
        require((0 until protocols.length()).any { integral(protocols.get(it)) == 1L })
        return generation to hash
    }

    private fun row(value: JSONObject, scope: Scope): Row {
        val row = Row(text(value.get("id")), text(value.get("provider")), text(value.get("sid")),
            text(value.get("text")), integral(value.get("position")), text(value.get("attempt")),
            State.entries.first { it.name.lowercase() == text(value.get("state")) },
            optionalNumber(value.get("deadline")), optionalString(value.get("receipt")),
            integral(value.get("epoch")), number(value.get("created")))
        require(row.id.matches(Regex("[A-Za-z0-9_-]{16,80}")) && row.attempt.isNotBlank())
        require(row.provider == scope.provider && row.sid == scope.sid && row.position > 0)
        return row
    }

    private fun item(value: JSONObject, scope: Scope, snapshot: Boolean): Item {
        val wrappers = listOf("message", "native_message", "attempt_receipt", "reset_epoch").count { value.has(it) }
        require(wrappers <= 1)
        if (wrappers != 0) require(!value.has("state") && !value.has("position") && !value.has("role"))
        if (value.has("message")) return Item.Message(row(jsonObject(value.get("message")), scope))
        if (value.has("native_message")) return Item.Native(fact(jsonObject(value.get("native_message")), scope, true))
        if (value.has("attempt_receipt")) return Item.AttemptReceipt(fact(jsonObject(value.get("attempt_receipt")), scope, false))
        if (!snapshot && value.has("reset_epoch")) return Item.Reset(integral(value.get("reset_epoch")))
        require(!snapshot)
        return Item.Message(row(value, scope))
    }

    private fun fact(value: JSONObject, scope: Scope, native: Boolean): Fact {
        val sid = text(value.get("sid"))
        val eventId = text(value.get("event_id"))
        require(sid == scope.sid && eventId.isNotBlank())
        val inode = if (value.has("inode")) integral(value.get("inode")) else null
        val offset = if (value.has("offset")) integral(value.get("offset")) else null
        val end = if (value.has("end")) integral(value.get("end")) else null
        if (native) {
            require(inode != null && inode > 0 && offset != null && end != null && end > offset)
            val row = jsonObject(value.get("row"))
            require(integral(row.get("offset")) == offset && integral(row.get("end")) == end)
            if (row.has("inode")) require(integral(row.get("inode")) == inode)
            require(row.get("role") is String && row.get("text") is String)
            for (field in listOf("id", "read_id")) if (row.has(field)) require(row.get(field) is String)
            if (row.has("segment")) integral(row.get("segment"))
            ChatHistoryProtocol.parseRow(row)
        }
        return Fact(sid, eventId, inode, offset, end, value.toString())
    }

    fun rejectedMutation(reply: String, id: Long): Boolean {
        require(reply.toByteArray(Charsets.UTF_8).size <= ChatHistoryProtocol.MAX_REPLY_BYTES)
        val envelope = JSONObject(reply)
        require(integral(envelope.get("id")) == id)
        if (!envelope.has("wire")) return false
        require(!envelope.has("message_protocol") && !envelope.has("result"))
        return text(envelope.get("wire")) == "@mp\t$id\thistory\terror\t-\t0\n${TranscriptProtocol.END}\n"
    }

    fun mutation(reply: String, id: Long, scope: Scope, messageId: String?, text: String? = null): Result {
        val result = result(reply, id)
        val value = result.get("message")
        val row = if (value == JSONObject.NULL) null else row(jsonObject(value), scope)
        if (row != null) {
            require(row.id == messageId)
            if (text != null) require(row.text == text)
        }
        return Result(integral(result.get("epoch")), integral(result.get("seq")), row)
    }

    fun requiredMutation(reply: String, id: Long, scope: Scope, messageId: String, text: String? = null): Result =
        mutation(reply, id, scope, messageId, text).also { require(it.row != null) }

    fun reset(reply: String, id: Long): Result {
        val result = result(reply, id)
        return Result(integral(result.get("epoch")), integral(result.get("seq")), null)
    }

    fun snapshot(reply: String, id: Long, scope: Scope): SnapshotPage {
        val result = result(reply, id)
        val values = array(result.get("items"))
        val next = result.get("next_cursor")
        return SnapshotPage(text(result.get("snapshot_id")).also { require(it.isNotBlank()) },
            integral(result.get("epoch")), integral(result.get("seq")),
            (0 until values.length()).map { item(jsonObject(values.get(it)), scope, true) },
            if (next == JSONObject.NULL) null else integral(next))
    }

    fun events(reply: String, id: Long, scope: Scope, after: Long): Events {
        val result = result(reply, id)
        val values = array(result.get("events"))
        var previous = after
        val events = (0 until values.length()).map {
            val event = jsonObject(values.get(it))
            val seq = integral(event.get("seq"))
            require(seq > previous)
            previous = seq
            Event(seq, item(jsonObject(event.get("event")), scope, false))
        }
        val next = integral(result.get("next_after"))
        val head = integral(result.get("seq"))
        require(next == previous && head >= next)
        require(head == next || events.isNotEmpty())
        return Events(integral(result.get("epoch")), head, events, next)
    }
}
