package dev.multiprompt.companion.room

private val mention = Regex("(?<![\\w@.\\\\+-])@([A-Za-z0-9_-]+)")

private val trailingMention = Regex("(^|\\s)@[A-Za-z0-9_-]*$")

fun roomMentions(text: String): List<String> = mention.findAll(text).map { it.groupValues[1] }.toList()

fun roomRecipients(text: String, aliases: List<String>): List<String> {
    val mentioned = roomMentions(text).toSet()
    return aliases.filter { it in mentioned }
}

fun roomShowsMentions(draft: String): Boolean = trailingMention.containsMatchIn(draft)
