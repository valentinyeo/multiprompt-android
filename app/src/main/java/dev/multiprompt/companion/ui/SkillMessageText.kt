package dev.multiprompt.companion.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.multiprompt.companion.skills.NativeSkill
import dev.multiprompt.companion.skills.NativeSkills
import dev.multiprompt.companion.skills.SkillSpan

internal class SkillPillText(private val spans: List<SkillSpan>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text)
            spans.forEach { span ->
                if (span.start in 0 until span.end && span.end <= length) {
                    addStyle(SpanStyle(color = Kit.Text), span.start, span.end)
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

internal fun TextLayoutResult.scrollsInsideComposer(): Boolean =
    multiParagraph.didExceedMaxLines || lineCount > ReaderComposerMaxLines

@Composable
internal fun Modifier.skillPills(spans: List<SkillSpan>, layout: TextLayoutResult?): Modifier {
    if (spans.isEmpty() || layout == null || layout.scrollsInsideComposer()) return this
    val padX = with(LocalDensity.current) { 2.dp.toPx() }
    val padY = with(LocalDensity.current) { 1.dp.toPx() }
    val stroke = with(LocalDensity.current) { Kit.OutlineWidth.toPx() }
    return drawBehind {
        val shown = layout.layoutInput.text.length
        spans.forEach { span ->
            if (span.start < 0 || span.end > shown || span.end <= span.start) return@forEach
            val first = layout.getLineForOffset(span.start)
            val last = layout.getLineForOffset(span.end - 1)
            for (line in first..last) {
                val start = maxOf(span.start, layout.getLineStart(line))
                val nextLine = if (line + 1 < layout.lineCount) layout.getLineStart(line + 1) else shown
                var end = minOf(span.end, nextLine)
                if (end > start && layout.layoutInput.text[end - 1] == '\n') end--
                if (end <= start) continue
                val leftBox = layout.getBoundingBox(start)
                val rightBox = layout.getBoundingBox(end - 1)
                val rect = Rect(
                    left = minOf(leftBox.left, rightBox.left) - padX,
                    top = minOf(leftBox.top, rightBox.top) - padY,
                    right = maxOf(leftBox.right, rightBox.right) + padX,
                    bottom = maxOf(leftBox.bottom, rightBox.bottom) + padY,
                )
                val radius = CornerRadius(rect.height / 2f)
                drawRoundRect(Kit.Surface, rect.topLeft, rect.size, radius)
                drawRoundRect(Kit.Outline, rect.topLeft, rect.size, radius, style = Stroke(stroke))
            }
        }
    }
}

@Composable
internal fun SkillMessageText(
    text: String,
    skills: List<NativeSkill>,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    sunlight: Boolean,
    modifier: Modifier = Modifier,
    linkColor: Color? = null,
    matchBase: Int = 0,
    matchHits: List<ChatFind.Hit> = emptyList(),
    activeHit: ChatFind.Hit? = null,
) {
    val spans = remember(text, skills) { NativeSkills.spans(text, skills) }
    if (spans.isEmpty()) {
        Text(
            (if (linkColor == null) terminalLinks(text, sunlight) else terminalLinks(text, sunlight, linkColor = linkColor))
                .marking(matchBase, matchHits, activeHit),
            modifier = modifier,
            fontFamily = chatFontFamily(),
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontFamily = chatFontFamily()
    val pillStyle = TextStyle(fontFamily = fontFamily, fontSize = fontSize, lineHeight = fontSize, color = Kit.Text)
    val annotated = remember(text, spans, sunlight, linkColor, matchBase, matchHits, activeHit) {
        skillAnnotated(text, spans, sunlight, linkColor).marking(matchBase, matchHits, activeHit)
    }
    val padX = with(density) { 8.dp.roundToPx() }
    val padY = with(density) { 1.dp.roundToPx() }
    val inline = spans.mapIndexed { index, span ->
        val label = text.substring(span.start, span.end)
        val paint = skillHitPaint(span.start, span.end, matchBase, matchHits, activeHit)
        val measured = measurer.measure(AnnotatedString(label), style = pillStyle).size
        val width = with(density) { (measured.width + padX * 2).toSp() }
        val height = with(density) { (measured.height + padY * 2).coerceAtLeast(1).toSp() }
        "skill$index" to InlineTextContent(
            Placeholder(width, height, PlaceholderVerticalAlign.TextCenter),
        ) { _ ->
            KitSkillPill(
                label,
                style = pillStyle,
                current = paint == SkillHitPaint.Current,
                marked = paint == SkillHitPaint.Other,
            )
        }
    }.toMap()
    BasicText(
        text = annotated,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, lineHeight = lineHeight, fontFamily = fontFamily),
        inlineContent = inline,
    )
}

internal fun skillAnnotated(
    text: String,
    spans: List<SkillSpan>,
    sunlight: Boolean,
    linkColor: Color?,
): AnnotatedString = buildAnnotatedString {
    var from = 0
    spans.forEachIndexed { index, span ->
        if (span.start > from) appendLinked(text.substring(from, span.start), sunlight, linkColor)
        appendInlineContent("skill$index", text.substring(span.start, span.end.coerceAtMost(text.length)))
        from = span.end
    }
    if (from < text.length) appendLinked(text.substring(from), sunlight, linkColor)
}

private fun AnnotatedString.Builder.appendLinked(slice: String, sunlight: Boolean, linkColor: Color?) {
    if (slice.isEmpty()) return
    append(if (linkColor == null) terminalLinks(slice, sunlight) else terminalLinks(slice, sunlight, linkColor = linkColor))
}
