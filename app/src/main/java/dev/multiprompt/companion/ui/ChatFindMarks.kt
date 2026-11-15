package dev.multiprompt.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

internal fun AnnotatedString.marking(base: Int, hits: List<ChatFind.Hit>, active: ChatFind.Hit?): AnnotatedString {
    if (hits.isEmpty() || isEmpty()) return this
    val end = base + length
    val ordered = hits.sortedBy { hit ->
        if (active != null && hit.message == active.message && hit.start == active.start && hit.end == active.end) 1 else 0
    }
    val builder = AnnotatedString.Builder(this)
    var painted = false
    ordered.forEach { hit ->
        if (hit.end <= base || hit.start >= end) return@forEach
        val from = (hit.start - base).coerceAtLeast(0)
        val to = (hit.end - base).coerceAtMost(length)
        if (from >= to) return@forEach
        val current = active != null && hit.message == active.message && hit.start == active.start && hit.end == active.end
        builder.addStyle(
            SpanStyle(
                background = if (current) Kit.Text else Kit.Outline,
                color = if (current) Kit.Page else Color.Unspecified,
            ),
            from,
            to,
        )
        painted = true
    }
    return if (painted) builder.toAnnotatedString() else this
}
