package dev.multiprompt.companion.ui

internal object ChatChoices {
    fun indices(blocks: List<MdBlock>): Set<Int> {
        var latest = emptySet<Int>()
        var group = mutableListOf<Int>()
        var previous = 0
        var letters = false
        var ready = false
        fun finish() {
            if (group.size >= 2) latest = group.toSet()
            group = mutableListOf()
            previous = 0
        }
        blocks.forEachIndexed { index, block ->
            val item = block as? MdBlock.ListItem
            val marker = item?.marker?.removeSuffix(".").orEmpty()
            val letter = marker.length == 1 && marker[0].isLetter()
            val ordinal = if (letter) marker.uppercase()[0] - 'A' + 1 else marker.toIntOrNull() ?: 0
            if (item == null || item.depth != 0 || ordinal < 1 || item.runs.all { it.text.isBlank() }) {
                finish()
                val runs = when (block) {
                    is MdBlock.Paragraph -> block.runs
                    is MdBlock.Heading -> block.runs
                    else -> emptyList()
                }
                val prompt = runs.joinToString("") { it.text }.trim().lineSequence().last().trim()
                ready = prompt.endsWith("?") || (prompt.endsWith(":") &&
                    listOf("pick one", "choose", "which", "options", "your call").any { prompt.contains(it, ignoreCase = true) })
                if (ready) latest = emptySet()
            } else {
                if (ordinal != previous + 1 || (group.isNotEmpty() && letter != letters)) {
                    finish()
                    ready = false
                }
                if ((ordinal == 1 && ready) || group.isNotEmpty()) {
                    group.add(index)
                    previous = ordinal
                    letters = letter
                }
            }
        }
        finish()
        return latest
    }

    fun label(marker: String, readerStyle: Boolean): String {
        val raw = marker.removeSuffix(".")
        var ordinal = raw.toIntOrNull() ?: return raw.uppercase()
        if (!readerStyle || ordinal < 1) return raw
        return buildString {
            while (ordinal > 0) {
                insert(0, ('A'.code + (ordinal - 1) % 26).toChar())
                ordinal = (ordinal - 1) / 26
            }
        }
    }

    fun answer(item: MdBlock.ListItem, readerStyle: Boolean): String =
        "${label(item.marker, readerStyle)}: ${item.runs.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()}"
}
