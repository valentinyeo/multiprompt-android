package dev.multiprompt.companion.reader

import org.json.JSONObject

object ChatSteps {
    data class Step(val id: String, val text: String, val diff: String, val detail: String, val offset: Long) {
        val output: List<String> get() = preview(diff) + preview(detail)
    }

    data class Group(val key: String, val steps: List<Step>)

    data class Placement(val above: Map<String, Group> = emptyMap(), val trailing: Group? = null)

    fun of(rows: List<ChatHistoryProtocol.Row>): List<Step> {
        val calls = LinkedHashMap<String, Step>()
        for (row in rows) {
            if (row.role !in ChatHistoryProtocol.STEP_ROLES) continue
            val metadata = if (row.normalized.isEmpty()) JSONObject() else JSONObject(row.normalized)
            val diff = metadata.optString("diff")
            val detail = metadata.optString("detail")
            if (row.role == "tool") {
                val id = row.id.ifEmpty { "@${row.offset}" }
                calls[id] = Step(id, row.text, diff, detail, row.offset)
            } else {
                val call = calls[row.id] ?: continue
                calls[row.id] = call.copy(diff = call.diff.ifEmpty { diff },
                    detail = listOf(call.detail, detail).filter { it.isNotEmpty() }.joinToString("\n\n"))
            }
        }
        return calls.values.filter { it.text.isNotBlank() }.sortedBy { it.offset }
    }

    fun place(messages: List<ChatHistoryMessage>, steps: List<Step>): Placement {
        if (steps.isEmpty()) return Placement()
        val lastReal = messages.indexOfLast { it.end > 0 }
        val above = LinkedHashMap<String, Group>()
        var next = 0
        for ((index, message) in messages.withIndex()) {
            if (next == steps.size) break
            val start = next
            if (message.end > 0) {
                while (next < steps.size && steps[next].offset < message.offset) next++
            } else if (index > lastReal) {
                next = steps.size
            }
            if (next > start) above[message.key] = group(steps.subList(start, next))
        }
        return Placement(above, if (next < steps.size) group(steps.subList(next, steps.size)) else null)
    }

    fun label(group: Group, open: Boolean): String =
        (if (open) "⌄ " else "› ") + group.steps.size + if (group.steps.size == 1) " step" else " steps"

    internal fun preview(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        var cut = text.take(320)
        if (cut.length < text.length && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut.split('\n').take(4).filter { it.isNotBlank() }
    }

    private fun group(steps: List<Step>): Group = Group(steps.first().id, steps.toList())
}
