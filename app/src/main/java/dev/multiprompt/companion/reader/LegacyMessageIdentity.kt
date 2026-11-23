package dev.multiprompt.companion.reader

internal object LegacyMessageIdentity {
    private val MENTION_NOTE = Regex("\\(This line is addressed to .*?or relay it unless you are also asked\\.\\)")
    private val LIVE_MARKER = Regex("\\{live-[A-Za-z0-9_.:-]{1,64}\\}", RegexOption.IGNORE_CASE)
    private val WORD_SPLIT = Regex("[\\s\\u00A0]+")
    private val SKILL = Regex("/[A-Za-z0-9:_-]+")
    private const val SKILL_LIMIT = 64

    fun same(left: String, right: String): Boolean {
        val a = left.replace("\r", "").trimEnd(' ')
        val b = right.replace("\r", "").trimEnd(' ')
        if (a == b) return true
        return sameSkillText(words(a), words(b))
    }

    fun seconds(timestamp: String): Double? =
        runCatching { java.time.Instant.parse(timestamp).toEpochMilli() / 1000.0 }
            .recoverCatching {
                java.time.LocalDateTime.parse(timestamp).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() / 1000.0
            }
            .getOrNull()

    fun shared(message: ChatHistoryMessage, card: MessageProtocol.Row): Boolean =
        message.role == "user" && seconds(message.timestamp)?.let { it >= card.created - 2.0 } == true && same(message.text, card.text)

    fun delivered(message: ChatHistoryMessage, prompt: QueuedMessages.SentPrompt, sid: String, inode: Long): Boolean =
        prompt.sid.isNotEmpty() && prompt.sid == sid && prompt.inode == inode && message.role == "user" &&
            message.offset >= prompt.afterOffset && same(message.text, prompt.text)

    fun replyBoundary(message: ChatHistoryMessage): Boolean = message.role == "user" ||
        message.role == "assistant" && message.requestId.startsWith("history-") &&
            (message.text == "---\nFresh start\n---" || message.text.startsWith("---\nSwitched AI: ") && message.text.endsWith("\n---"))

    fun reply(message: ChatHistoryMessage, block: HostBlock, owner: ChatHistoryMessage?): Boolean =
        block.done && message.role == "assistant" && same(message.text, block.text) &&
            (block.messageId == null || message.requestId.startsWith("history-") && when (owner?.role) {
                "user" -> block.messageId == owner.requestId
                "assistant" -> block.messageId.startsWith("fresh-")
                else -> false
            })

    private fun words(text: String): List<String> =
        text.replace(MENTION_NOTE, " ").replace(LIVE_MARKER, "").split(WORD_SPLIT).filter { it.isNotEmpty() }

    private fun skill(word: String): Boolean = word.length >= 2 && SKILL.matches(word)

    private fun sameSkillText(left: List<String>, right: List<String>): Boolean {
        var index = 0
        var leftSkills = 0
        var rightSkills = 0
        for (word in left) {
            if (skill(word)) {
                leftSkills++
                if (leftSkills > SKILL_LIMIT) return false
                continue
            }
            while (index < right.size && skill(right[index])) {
                rightSkills++
                index++
            }
            if (index >= right.size || right[index] != word) return false
            index++
        }
        while (index < right.size) {
            if (!skill(right[index])) return false
            rightSkills++
            index++
        }
        if (leftSkills == 0 || leftSkills != rightSkills) return false
        return left.filter(::skill).groupingBy { it }.eachCount() == right.filter(::skill).groupingBy { it }.eachCount()
    }
}
