package dev.multiprompt.companion.reader

data class HostBlock(val item: String, val kind: String, val text: String, val done: Boolean, val turn: String? = null)

data class HostStream(
    val turn: String? = null,
    val running: Boolean = false,
    val blocks: List<HostBlock> = emptyList(),
    val request: HostRequest? = null,
    val earlier: List<HostBlock> = emptyList(),
) {
    val spoken: List<HostBlock>
        get() = blocks.filter { it.kind == "text" && it.text.isNotBlank() }

    fun apply(event: MessageProtocol.HostEvent): HostStream = when {
        event.request != null -> copy(request = event.request)
        event.resolved != null -> if (request?.id == event.resolved) copy(request = null) else this
        event.kind == "turn.started" -> next(event.turn)
        event.kind == "turn.ended" -> copy(running = false)
        event.kind == "host.status" -> if (event.state == "stopped") copy(running = false) else this
        event.kind.startsWith("text.") || event.kind.startsWith("thinking.") -> upsert(event)
        else -> this
    }

    private fun next(next: String?): HostStream {
        val finished = spoken.filter { it.done }.map { it.copy(turn = turn) }
        return HostStream(next, running = true, request = request, earlier = finished.ifEmpty { earlier })
    }

    private fun upsert(event: MessageProtocol.HostEvent): HostStream {
        val body = event.text ?: return this
        val fresh = if (event.turn != null && event.turn != turn) next(event.turn) else this
        val item = event.item ?: event.kind
        val block = HostBlock(item, event.kind.substringBefore('.'), body, event.done)
        val existing = fresh.blocks.indexOfFirst { it.item == item }
        val blocks = if (existing < 0) fresh.blocks + block else fresh.blocks.toMutableList().also { it[existing] = block }
        return fresh.copy(blocks = blocks)
    }
}
