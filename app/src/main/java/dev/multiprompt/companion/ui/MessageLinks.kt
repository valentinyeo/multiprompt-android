package dev.multiprompt.companion.ui

object MessageLinks {
    fun links(text: String): List<String> {
        val urls = ArrayList<String>()
        fun collect(runs: List<InlineRun>) {
            runs.forEach { run -> run.url?.let { if (!run.code) urls += it } }
        }
        MarkdownParser.parse(LiveMention.visible(text)).forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> collect(block.runs)
                is MdBlock.Heading -> collect(block.runs)
                is MdBlock.ListItem -> collect(block.runs)
                is MdBlock.Quote -> collect(block.runs)
                is MdBlock.Table -> {
                    block.header.forEach { collect(it) }
                    block.rows.forEach { row -> row.forEach { collect(it) } }
                }
                is MdBlock.Code, MdBlock.Rule -> Unit
            }
        }
        return urls
    }

    fun first(text: String): String? = links(text).firstOrNull()
}
