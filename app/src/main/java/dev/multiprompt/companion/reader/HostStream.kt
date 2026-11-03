package dev.multiprompt.companion.reader

data class HostBlock(val item: String, val kind: String, val text: String, val done: Boolean)

data class HostStream(
    val turn: String? = null,
    val running: Boolean = false,
    val blocks: List<HostBlock> = emptyList(),
) {
    val spoken: List<HostBlock>
        get() = blocks.filter { it.kind == "text" && it.text.isNotBlank() }

    fun apply(event: MessageProtocol.HostEvent): HostStream = when {
        event.kind == "turn.started" -> HostStream(event.turn, running = true)
        event.kind == "turn.ended" -> copy(running = false)
        event.kind == "host.status" -> if (event.state == "stopped") copy(running = false) else this
        event.kind.startsWith("text.") || event.kind.startsWith("thinking.") -> upsert(event)
        else -> this
    }

    private fun upsert(event: MessageProtocol.HostEvent): HostStream {
        val body = event.text ?: return this
        val fresh = if (event.turn != null && event.turn != turn) HostStream(event.turn, running = true) else this
        val item = event.item ?: event.kind
        val block = HostBlock(item, event.kind.substringBefore('.'), body, event.done)
        val existing = fresh.blocks.indexOfFirst { it.item == item }
        val blocks = if (existing < 0) fresh.blocks + block else fresh.blocks.toMutableList().also { it[existing] = block }
        return fresh.copy(blocks = blocks)
    }
}
