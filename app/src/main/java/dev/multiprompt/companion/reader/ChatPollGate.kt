package dev.multiprompt.companion.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatPollGate : AutoCloseable {
    private val leases = mutableSetOf<Any>()
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private var closed = false
    private val listeners = mutableSetOf<(Boolean) -> Unit>()

    @Synchronized
    internal fun onChange(listener: (Boolean) -> Unit): AutoCloseable {
        listeners += listener
        listener(_active.value)
        return AutoCloseable { synchronized(this) { listeners -= listener } }
    }

    private fun publish() {
        val value = leases.isNotEmpty()
        if (_active.value == value) return
        listeners.forEach { it(value) }
        _active.value = value
    }

    internal fun launchIn(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        active.collectLatest { visible -> if (visible) coroutineScope(block) }
    }

    @Synchronized
    fun acquire(): AutoCloseable {
        if (closed) return AutoCloseable {}
        val token = Any()
        leases += token
        publish()
        return AutoCloseable { release(token) }
    }

    @Synchronized
    private fun release(token: Any) {
        leases -= token
        publish()
    }

    @Synchronized
    override fun close() {
        closed = true
        leases.clear()
        publish()
        listeners.clear()
    }
}
