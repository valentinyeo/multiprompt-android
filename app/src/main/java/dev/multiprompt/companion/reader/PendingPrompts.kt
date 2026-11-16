package dev.multiprompt.companion.reader

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PendingPrompts {
    data class Entry(val id: String, val text: String, val issuing: Boolean = false, val error: String? = null) {
        fun message() = ChatHistoryMessage("@pending:$id", "user", text, "", 0, 0,
            requestId = id, pending = error == null, delivery = if (error == null) "Waiting for chat connection" else ChatHistory.NOT_SENT)
    }

    private val _state = MutableStateFlow<List<Entry>>(emptyList())
    val state = _state.asStateFlow()
    private val _accepted = MutableStateFlow<Set<String>>(emptySet())
    val accepted = _accepted.asStateFlow()

    @Synchronized fun enqueue(text: String): Entry = Entry(UUID.randomUUID().toString(), text).also {
        _state.value = _state.value + it
    }

    @Synchronized fun next(ready: Boolean): Entry? {
        if (!ready || _state.value.any { it.issuing }) return null
        val entry = _state.value.firstOrNull { it.error == null } ?: return null
        _state.value = _state.value.map { if (it.id == entry.id) it.copy(issuing = true) else it }
        return entry
    }

    @Synchronized fun complete(id: String, admission: PromptAdmission) {
        when (admission) {
            is PromptAdmission.Rejected -> _state.value = _state.value.map {
                if (it.id == id) it.copy(issuing = false, error = admission.reason.takeUnless(::retryable)) else it
            }
            is PromptAdmission.Recorded -> {
                _state.value = _state.value.filterNot { it.id == id }
                _accepted.value = (_accepted.value + id).toList().takeLast(64).toSet()
            }
            else -> error("Pending prompts must enter the message journal before removal")
        }
    }

    @Synchronized fun retry(id: String): Boolean {
        if (_state.value.none { it.id == id && !it.issuing }) return false
        _state.value = _state.value.map { if (it.id == id) it.copy(error = null) else it }
        return true
    }

    @Synchronized fun remove(id: String): Boolean {
        if (_state.value.none { it.id == id && !it.issuing }) return false
        _state.value = _state.value.filterNot { it.id == id }
        return true
    }

    companion object {
        fun retryable(reason: String): Boolean = reason in setOf("Chat is disconnected", "Chat is not ready", "Chat is catching up")
    }
}
