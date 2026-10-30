package dev.multiprompt.companion.reader

data class MessageReplica(
    val epoch: Long,
    val cursor: Long,
    val messages: Map<String, MessageProtocol.Row> = emptyMap(),
    val nativeFacts: Map<String, MessageProtocol.Fact> = emptyMap(),
    val attemptReceipts: Map<String, MessageProtocol.Fact> = emptyMap(),
) {
    fun apply(events: MessageProtocol.Events): MessageReplica {
        require(events.epoch == epoch)
        var next = this
        for (event in events.events) {
            require(event.seq > next.cursor)
            next = next.applyItem(event.item).copy(cursor = event.seq)
        }
        require(next.cursor == events.next)
        return next
    }

    private fun applyItem(item: MessageProtocol.Item): MessageReplica = when (item) {
        is MessageProtocol.Item.Message -> copy(messages = messages + (item.row.id to item.row))
        is MessageProtocol.Item.Native -> copy(nativeFacts = nativeFacts + (item.fact.eventId to item.fact))
        is MessageProtocol.Item.AttemptReceipt -> copy(attemptReceipts = attemptReceipts + (item.fact.eventId to item.fact))
        is MessageProtocol.Item.Reset -> error("Reset requires a new snapshot")
    }

    companion object {
        fun snapshot(pages: List<MessageProtocol.SnapshotPage>): MessageReplica {
            require(pages.isNotEmpty() && pages.last().next == null)
            val first = pages.first()
            var offset = 0L
            var replica = MessageReplica(first.epoch, first.seq)
            pages.forEachIndexed { index, page ->
                require(page.id == first.id && page.epoch == first.epoch && page.seq == first.seq)
                page.items.forEach { replica = replica.applyItem(it) }
                offset += page.items.size
                if (index < pages.lastIndex) require(page.next == offset) else require(page.next == null)
            }
            return replica
        }
    }
}
