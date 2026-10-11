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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders a Markdown chat reply: headings, lists, quotes, code, tables, rules and tappable links. */
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
    val lineHeight = (fontSize.value * 1.5f).sp
    androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = ChatFontFamily)) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    markdownAnnotated(block.runs, linkColor, codeBg),
                    fontSize = fontSize, lineHeight = lineHeight, color = color,
                )
                is MdBlock.Heading -> Text(
                    markdownAnnotated(block.runs, linkColor, codeBg),
                    fontSize = (fontSize.value * headingScale(block.level)).sp,
                    lineHeight = (fontSize.value * headingScale(block.level) * 1.35f).sp,
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
                        .background(codeBg, RoundedCornerShape(4.dp))
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
    val cell: Dp = 120.dp
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    Column(
        Modifier.horizontalScroll(rememberScrollState())
            .background(color.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
    ) {
        (listOf(table.header) + table.rows).forEachIndexed { rowIndex, row ->
            Row {
                for (c in 0 until columns) {
                    Text(
                        markdownAnnotated(row.getOrNull(c).orEmpty(), linkColor, codeBg),
                        fontSize = (fontSize.value * 0.92f).sp,
                        fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                        color = color,
                        modifier = Modifier.width(cell).padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
            if (rowIndex == 0) Box(Modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = 0.3f)))
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
