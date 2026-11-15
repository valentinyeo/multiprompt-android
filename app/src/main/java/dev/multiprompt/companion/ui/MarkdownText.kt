package dev.multiprompt.companion.ui

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
    imagePreviews: Boolean = false,
    matchHits: List<ChatFind.Hit> = emptyList(),
    activeHit: ChatFind.Hit? = null,
) {
    val blocks = remember(text) { MarkdownParser.parse(LiveMention.visible(text)) }
    val codeBg = color.copy(alpha = 0.10f)
    val codeFontFamily = if (LocalReaderStyle.current) FontFamily.SansSerif else FontFamily.Monospace
    val scale = if (LocalReaderStyle.current) fontSize.value / ReaderTextSize.value else if (LocalMaterial3.current) fontSize.value / 15f else 1f
    val lineHeight = chatLineHeight((fontSize.value * 1.5f).sp, scale)
    val bringMatch = remember { BringIntoViewRequester() }
    LaunchedEffect(activeHit?.message, activeHit?.start, activeHit?.end) {
        if (activeHit != null && matchHits.any { it.message == activeHit.message && it.start == activeHit.start && it.end == activeHit.end }) {
            withFrameNanos { }
            runCatching { bringMatch.bringIntoView() }
        }
    }
    fun mark(value: AnnotatedString, at: Int) = value.marking(at, matchHits, activeHit)
    @Composable
    fun Modifier.pin(at: Int, length: Int): Modifier {
        val hit = activeHit
        return if (hit != null && length > 0 && hit.start >= at && hit.start < at + length) this.bringIntoViewRequester(bringMatch) else this
    }
    androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = chatFontFamily())) {
    Column(if (LocalReaderStyle.current) modifier else modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var cursor = 0
        blocks.forEachIndexed { blockIndex, block ->
            val piece = MarkdownParser.blockText(block)
            val base = cursor
            when (block) {
                is MdBlock.Paragraph -> if (matchHits.isEmpty()) {
                    if (imagePreviews) {
                        ChatImageRuns(block.runs) { runs ->
                            Text(markdownAnnotated(runs, linkColor, codeBg, codeFontFamily),
                                fontSize = fontSize, lineHeight = lineHeight, color = color)
                        }
                    } else {
                        Text(markdownAnnotated(block.runs, linkColor, codeBg, codeFontFamily),
                            fontSize = fontSize, lineHeight = lineHeight, color = color)
                    }
                } else {
                    val lines = if (imagePreviews) inlineMatchLines(block.runs) else listOf(0 to block.runs)
                    Column {
                        lines.forEach { (offset, line) ->
                            val length = line.sumOf { it.text.length }
                            Text(
                                mark(markdownAnnotated(line, linkColor, codeBg, codeFontFamily), base + offset),
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                color = color,
                                modifier = Modifier.pin(base + offset, length),
                            )
                            if (imagePreviews) ChatImagePreviews(line)
                        }
                    }
                }
                is MdBlock.Heading -> Text(
                    mark(markdownAnnotated(block.runs, linkColor, codeBg, codeFontFamily), base),
                    fontSize = if (LocalReaderStyle.current) ReaderHeadingTextSize * scale else if (LocalMaterial3.current) MaterialTheme.typography.titleMedium.fontSize * scale else (fontSize.value * headingScale(block.level)).sp,
                    lineHeight = if (LocalReaderStyle.current) ReaderHeadingLineHeight * scale else if (LocalMaterial3.current) MaterialTheme.typography.titleMedium.lineHeight * scale else (fontSize.value * headingScale(block.level) * 1.35f).sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier.padding(top = 4.dp).pin(base, piece.length),
                )
                is MdBlock.ListItem -> {
                    val runsAt = base + block.marker.length
                    Row(Modifier.padding(start = (block.depth * 16).dp)) {
                        Text(
                            mark(AnnotatedString(block.marker), base),
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            color = color,
                            modifier = Modifier.width(if (block.marker.length > 2) 28.dp else 18.dp).pin(base, block.marker.length),
                        )
                        Text(
                            mark(markdownAnnotated(block.runs, linkColor, codeBg, codeFontFamily), runsAt),
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            color = color,
                            modifier = Modifier.weight(1f).pin(runsAt, piece.length - block.marker.length),
                        )
                    }
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .then(if (LocalMaterial3.current) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)) else Modifier)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (LocalMaterial3.current) MaterialTheme.colorScheme.primary else color.copy(alpha = 0.4f)))
                    Text(
                        mark(markdownAnnotated(block.runs, linkColor, codeBg, codeFontFamily), base),
                        fontSize = if (LocalMaterial3.current && !LocalReaderStyle.current) 13.sp else fontSize, lineHeight = lineHeight,
                        color = if (LocalMaterial3.current) MaterialTheme.colorScheme.onSurfaceVariant else color.copy(alpha = 0.85f),
                        fontStyle = if (LocalMaterial3.current) FontStyle.Normal else FontStyle.Italic,
                        modifier = Modifier.padding(start = 10.dp).weight(1f).pin(base, piece.length),
                    )
                }
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(codeBg, chromeShape(RoundedCornerShape(4.dp)))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                        .pin(base, piece.length),
                ) {
                    Text(
                        mark(AnnotatedString(block.text), base),
                        fontFamily = codeFontFamily,
                        fontSize = if (LocalReaderStyle.current) ReaderCodeTextSize * scale else (fontSize.value * 0.88f).sp,
                        lineHeight = if (LocalReaderStyle.current) ReaderCodeLineHeight * scale else (fontSize.value * 1.35f).sp,
                        color = color,
                        softWrap = false,
                    )
                }
                is MdBlock.Table -> MarkdownTable(
                    block,
                    fontSize,
                    color,
                    linkColor,
                    codeBg,
                    modifier = Modifier.pin(base, piece.length),
                    matchHits = matchHits,
                    activeHit = activeHit,
                    tableBase = base,
                )
                MdBlock.Rule -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                        .background(color.copy(alpha = 0.3f)),
                )
            }
            if (imagePreviews && (matchHits.isEmpty() || block !is MdBlock.Paragraph)) {
                val runs = when (block) {
                    is MdBlock.Heading -> block.runs
                    is MdBlock.ListItem -> block.runs
                    is MdBlock.Quote -> block.runs
                    is MdBlock.Table -> block.header.flatten() + block.rows.flatten().flatten()
                    else -> emptyList()
                }
                ChatImagePreviews(runs)
            }
            cursor += piece.length
            if (blockIndex < blocks.lastIndex) cursor += 1
        }
    }
    }
}

private fun inlineMatchLines(runs: List<InlineRun>): List<Pair<Int, List<InlineRun>>> {
    if (runs.none { run -> !run.code && run.url != null }) return listOf(0 to runs)
    val lines = ArrayList<Pair<Int, List<InlineRun>>>()
    var current = ArrayList<InlineRun>()
    var origin = 0
    var cursor = 0
    fun finishLine() {
        lines += origin to current
        current = ArrayList()
    }
    runs.forEach { run ->
        run.text.split('\n').forEachIndexed { index, part ->
            if (index > 0) {
                finishLine()
                cursor += 1
                origin = cursor
            }
            current += run.copy(text = part)
            cursor += part.length
        }
    }
    finishLine()
    return lines
}

@Composable
private fun MarkdownTable(
    table: MdBlock.Table,
    fontSize: TextUnit,
    color: Color,
    linkColor: Color,
    codeBg: Color,
    modifier: Modifier = Modifier,
    matchHits: List<ChatFind.Hit> = emptyList(),
    activeHit: ChatFind.Hit? = null,
    tableBase: Int = 0,
) {
    val material3 = LocalMaterial3.current
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    val rows = listOf(table.header) + table.rows
    val cellCount = rows.size * columns
    val dividerCount = if (material3) rows.size - 1 else 1
    var cellAt = tableBase
    Layout(
        modifier = modifier.testTag("md-table").horizontalScroll(rememberScrollState())
            .then(if (material3) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)) else Modifier)
            .background(if (material3) MaterialTheme.colorScheme.surface else color.copy(alpha = 0.05f), chromeShape(RoundedCornerShape(4.dp))),
        content = {
            rows.forEachIndexed { rowIndex, row ->
                for (c in 0 until columns) {
                    val runs = row.getOrNull(c).orEmpty()
                    val at = cellAt
                    val length = runs.sumOf { it.text.length }
                    cellAt += length
                    if (c < columns - 1) cellAt += 1
                    Text(
                        markdownAnnotated(runs, linkColor, codeBg, if (LocalReaderStyle.current) FontFamily.SansSerif else FontFamily.Monospace)
                            .marking(at, matchHits, activeHit),
                        fontSize = if (LocalReaderStyle.current) ReaderCodeTextSize * (fontSize.value / ReaderTextSize.value) else if (LocalMaterial3.current) MaterialTheme.typography.bodyMedium.fontSize * (fontSize.value / 15f) else (fontSize.value * 0.92f).sp,
                        lineHeight = if (LocalReaderStyle.current) ReaderCodeLineHeight * (fontSize.value / ReaderTextSize.value) else TextUnit.Unspecified,
                        fontWeight = if (rowIndex == 0) { if (LocalMaterial3.current) FontWeight.SemiBold else FontWeight.Bold } else FontWeight.Normal,
                        color = color,
                        modifier = Modifier.testTag("md-cell").padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
                if (rowIndex < rows.lastIndex) cellAt += 1
            }
            if (material3) Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer))
            repeat(dividerCount) {
                Box(Modifier.height(1.dp).background(if (material3) MaterialTheme.colorScheme.outlineVariant else color.copy(alpha = 0.3f)))
            }
        },
    ) { measurables, constraints ->
        val widths = (0 until columns).map { column ->
            rows.indices.maxOf { row -> measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity) }
                .coerceAtMost(280.dp.roundToPx())
        }
        val cells = measurables.take(cellCount).mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixedWidth(widths[index % columns]))
        }
        val heights = rows.indices.map { row -> (0 until columns).maxOf { cells[row * columns + it].height } }
        val width = widths.sum()
        val header = if (material3) measurables[cellCount].measure(Constraints.fixed(width, heights.first())) else null
        val dividers = measurables.takeLast(dividerCount).map { it.measure(Constraints.fixedWidth(width)) }
        layout(constraints.constrainWidth(width), constraints.constrainHeight(heights.sum() + dividers.sumOf { it.height })) {
            header?.placeRelative(0, 0)
            var y = 0
            rows.indices.forEach { row ->
                var x = 0
                for (column in 0 until columns) {
                    cells[row * columns + column].placeRelative(x, y)
                    x += widths[column]
                }
                y += heights[row]
                if (row == 0 && !material3 || material3 && row < rows.lastIndex) {
                    val divider = dividers[if (material3) row else 0]
                    divider.placeRelative(0, y)
                    y += divider.height
                }
            }
        }
    }
}

private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.35f
    2 -> 1.22f
    3 -> 1.1f
    else -> 1.0f
}

internal fun markdownAnnotated(runs: List<InlineRun>, linkColor: Color, codeBg: Color, codeFontFamily: FontFamily = FontFamily.Monospace): AnnotatedString =
    buildAnnotatedString {
        runs.forEach { run ->
            val style = SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) codeFontFamily else null,
                background = if (run.code) codeBg else Color.Unspecified,
                textDecoration = if (run.strike) TextDecoration.LineThrough else null,
            )
            val url = run.url
            if (url != null) {
                withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { withStyle(style) { append(run.text) } }
            } else {
                withStyle(style) { append(run.text) }
            }
        }
    }
