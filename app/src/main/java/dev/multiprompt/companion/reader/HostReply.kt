package dev.multiprompt.companion.reader

data class HostRequest(val kind: String, val id: String, val title: String) {
    fun cards(): List<PendingQuestion> = emptyList()
}

object HostReply {
    sealed interface Outcome {
        data object Sent : Outcome
        data object Gone : Outcome
        data class Failed(val message: String) : Outcome
    }

    fun approval(id: Long, sid: String, requestId: String, decision: String): String = ""
    fun question(id: Long, sid: String, requestId: String, answers: Map<String, String>, dismissed: Boolean): String = ""
    fun plan(id: Long, sid: String, requestId: String, feedback: String?): String = ""
    fun answer(card: PendingQuestion, choices: Set<Int>, other: String): String = ""
    fun decision(choice: Int): String = ""
    fun outcome(reply: String, id: Long): Outcome = Outcome.Failed("")
}
