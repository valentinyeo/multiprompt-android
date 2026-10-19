package dev.multiprompt.companion.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val codeBg = color.copy(alpha = 0.10f)
    val scale = if (LocalMaterial3.current) fontSize.value / MaterialTheme.typography.bodyLarge.fontSize.value else 1f
    val lineHeight = chatLineHeight((fontSize.value * 1.5f).sp, scale)
    androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = chatFontFamily())) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    markdownAnnotated(block.runs, linkColor, codeBg),
                    fontSize = fontSize, lineHeight = lineHeight, color = color,
                )
                is MdBlock.Heading -> Text(
                    markdownAnnotated(block.runs, linkColor, codeBg),
                    fontSize = if (LocalMaterial3.current) MaterialTheme.typography.titleMedium.fontSize * scale else (fontSize.value * headingScale(block.level)).sp,
                    lineHeight = if (LocalMaterial3.current) MaterialTheme.typography.titleMedium.lineHeight * scale else (fontSize.value * headingScale(block.level) * 1.35f).sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Text(
                        block.marker,
                        fontSize = fontSize, lineHeight = lineHeight, color = color,
                        modifier = Modifier.width(if (block.marker.length > 2) 28.dp else 18.dp),
                    )
                    Text(
                        markdownAnnotated(block.runs, linkColor, codeBg),
                        fontSize = fontSize, lineHeight = lineHeight, color = color,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(color.copy(alpha = 0.4f)))
                    Text(
                        markdownAnnotated(block.runs, linkColor, codeBg),
                        fontSize = fontSize, lineHeight = lineHeight,
                        color = color.copy(alpha = 0.85f),
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                }
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(codeBg, chromeShape(RoundedCornerShape(4.dp)))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        block.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = (fontSize.value * 0.88f).sp,
                        lineHeight = (fontSize.value * 1.35f).sp,
                        color = color,
                        softWrap = false,
                    )
                }
                is MdBlock.Table -> MarkdownTable(block, fontSize, color, linkColor, codeBg)
                MdBlock.Rule -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                        .background(color.copy(alpha = 0.3f)),
                )
            }
        }
    }
    }
}

@Composable
private fun MarkdownTable(
    table: MdBlock.Table,
    fontSize: TextUnit,
    color: Color,
    linkColor: Color,
    codeBg: Color,
) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    val rows = listOf(table.header) + table.rows
    Layout(
        modifier = Modifier.testTag("md-table").horizontalScroll(rememberScrollState())
            .background(color.copy(alpha = 0.05f), chromeShape(RoundedCornerShape(4.dp))),
        content = {
            rows.forEachIndexed { rowIndex, row ->
                for (c in 0 until columns) {
                    Text(
                        markdownAnnotated(row.getOrNull(c).orEmpty(), linkColor, codeBg),
                        fontSize = if (LocalMaterial3.current) MaterialTheme.typography.bodyMedium.fontSize * (fontSize.value / MaterialTheme.typography.bodyLarge.fontSize.value) else (fontSize.value * 0.92f).sp,
                        fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                        color = color,
                        modifier = Modifier.testTag("md-cell").padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            Box(Modifier.height(1.dp).background(color.copy(alpha = 0.3f)))
        },
    ) { measurables, constraints ->
        val widths = (0 until columns).map { column ->
            rows.indices.maxOf { row -> measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity) }
                .coerceAtMost(280.dp.roundToPx())
        }
        val cells = measurables.dropLast(1).mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixedWidth(widths[index % columns]))
        }
        val heights = rows.indices.map { row -> (0 until columns).maxOf { cells[row * columns + it].height } }
        val width = widths.sum()
        val divider = measurables.last().measure(Constraints.fixedWidth(width))
        layout(constraints.constrainWidth(width), constraints.constrainHeight(heights.sum() + divider.height)) {
            var y = 0
            rows.indices.forEach { row ->
                var x = 0
                for (column in 0 until columns) {
                    cells[row * columns + column].placeRelative(x, y)
                    x += widths[column]
                }
                y += heights[row]
                if (row == 0) {
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

internal fun markdownAnnotated(runs: List<InlineRun>, linkColor: Color, codeBg: Color): AnnotatedString =
    buildAnnotatedString {
        runs.forEach { run ->
            val style = SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
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
