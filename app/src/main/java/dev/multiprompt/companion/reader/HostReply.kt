package dev.multiprompt.companion.reader

import org.json.JSONObject

data class HostQuestion(val header: String, val question: String, val options: List<PendingQuestion.Option>, val multiSelect: Boolean)

data class HostRequest(
    val kind: String,
    val id: String,
    val title: String,
    val questions: List<HostQuestion> = emptyList(),
    val markdown: String = "",
) {
    fun cards(): List<PendingQuestion> = when (kind) {
        "approval" -> listOf(PendingQuestion(id, 0, title.ifBlank { "Allow this tool?" }, "Permission",
            HostReply.APPROVAL_LABELS.map { PendingQuestion.Option(it, "") }, multiSelect = false, other = false, provider = "claude", host = kind))
        "question" -> questions.mapIndexed { index, question ->
            PendingQuestion(id, index, question.question, question.header, question.options, question.multiSelect,
                other = true, provider = "claude", host = kind)
        }
        "plan" -> listOf(PendingQuestion(id, 0, markdown.ifBlank { title.ifBlank { "Plan ready" } }, "Plan",
            listOf(PendingQuestion.Option("Implement", "")), multiSelect = false, other = true, provider = "claude", host = kind))
        else -> emptyList()
    }

    companion object {
        fun parse(kind: String, payload: JSONObject): HostRequest? {
            val id = payload.optString("request_id").takeIf { it.isNotBlank() } ?: return null
            fun label() = listOf("title", "display_name", "tool").map { payload.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            return when (kind) {
                "approval.request" -> HostRequest("approval", id, label())
                "plan.proposed" -> HostRequest("plan", id, payload.optString("title"), markdown = payload.optString("markdown"))
                "question.request" -> {
                    val rows = payload.optJSONArray("questions") ?: return null
                    val questions = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { row ->
                        val options = row.optJSONArray("options")
                        HostQuestion(row.optString("header"), row.optString("question"),
                            (0 until (options?.length() ?: 0)).mapNotNull { options!!.optJSONObject(it) }.map {
                                PendingQuestion.Option(it.optString("label"), it.optString("description"))
                            }, row.optBoolean("multiSelect")).takeIf { it.question.isNotBlank() }
                    }
                    if (questions.isEmpty()) null else HostRequest("question", id, payload.optString("tool"), questions)
                }
                else -> null
            }
        }

        fun resolved(kind: String, payload: JSONObject): String? =
            payload.optString("request_id").takeIf { kind in RESOLVED && it.isNotBlank() }

        private val RESOLVED = setOf("approval.resolved", "question.resolved", "plan.resolved")
    }
}

object HostReply {
    val APPROVAL_LABELS = listOf("Approve", "Always allow this session", "Decline")
    private val DECISIONS = listOf("accept", "acceptForSession", "decline")

    sealed interface Outcome {
        data object Sent : Outcome
        data object Gone : Outcome
        data class Failed(val message: String) : Outcome
    }

    private fun open(id: Long, op: String, sid: String, requestId: String) =
        StringBuilder("{\"id\":$id,\"op\":${JSONObject.quote(op)},\"provider\":\"claude\",\"sid\":${JSONObject.quote(sid)},\"request_id\":${JSONObject.quote(requestId)}")

    fun approval(id: Long, sid: String, requestId: String, decision: String): String =
        open(id, "approval_reply", sid, requestId).append(",\"decision\":").append(JSONObject.quote(decision)).append('}').toString()

    fun question(id: Long, sid: String, requestId: String, answers: Map<String, String>, dismissed: Boolean): String =
        open(id, "question_reply", sid, requestId).append(",\"answers\":{")
            .append(answers.entries.joinToString(",") { JSONObject.quote(it.key) + ":" + JSONObject.quote(it.value) })
            .append("},\"dismissed\":").append(dismissed).append('}').toString()

    fun plan(id: Long, sid: String, requestId: String, feedback: String?): String =
        open(id, "plan_reply", sid, requestId).append(",\"action\":").append(JSONObject.quote(if (feedback == null) "implement" else "feedback"))
            .apply { if (feedback != null) append(",\"feedback\":").append(JSONObject.quote(feedback)) }.append('}').toString()

    fun answer(card: PendingQuestion, choices: Set<Int>, other: String): String {
        val labels = choices.sorted().mapNotNull { card.options.getOrNull(it)?.label }
        val typed = other.trim()
        return if (card.multiSelect) (labels + listOfNotNull(typed.ifEmpty { null })).joinToString(", ")
            else typed.ifEmpty { labels.firstOrNull().orEmpty() }
    }

    fun decision(choice: Int): String = DECISIONS[choice]

    fun outcome(reply: String, id: Long): Outcome {
        val envelope = JSONObject(reply)
        require(envelope.getLong("id") == id && envelope.getInt("message_protocol") == 1)
        val result = envelope.getJSONObject("result")
        return when (result.optString("status")) {
            "ok" -> Outcome.Sent
            "refused" -> if (result.optString("reason") == "unknown_request") Outcome.Gone else Outcome.Failed("The agent refused this answer")
            "unavailable" -> Outcome.Failed("The agent host is not running")
            else -> Outcome.Failed("Could not send the answer")
        }
    }
}
