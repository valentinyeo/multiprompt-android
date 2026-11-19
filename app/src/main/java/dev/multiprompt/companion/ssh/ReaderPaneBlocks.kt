package dev.multiprompt.companion.ssh

object ReaderPaneBlocks {
    const val PAGE = 200

    data class Window(val blocks: List<TmuxText.ReaderBlock>, val hasEarlier: Boolean)

    fun window(blocks: List<TmuxText.ReaderBlock>, page: Int): Window {
        if (page <= 0) return Window(emptyList(), blocks.isNotEmpty())
        val limit = page.toLong() * PAGE
        if (blocks.size <= limit) return Window(blocks, false)
        return Window(blocks.takeLast(limit.toInt()), true)
    }
}
