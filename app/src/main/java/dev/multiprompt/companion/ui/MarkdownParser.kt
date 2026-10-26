package dev.multiprompt.companion.ui

data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val url: String? = null,
)

sealed interface MdBlock {
    data class Paragraph(val runs: List<InlineRun>) : MdBlock
    data class Heading(val level: Int, val runs: List<InlineRun>) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class ListItem(val depth: Int, val marker: String, val runs: List<InlineRun>) : MdBlock
    data class Quote(val runs: List<InlineRun>) : MdBlock
    data class Table(val header: List<List<InlineRun>>, val rows: List<List<List<InlineRun>>>) : MdBlock
    data object Rule : MdBlock
}

object MarkdownParser {
    private val FENCE = Regex("^\\s*(```+|~~~+)\\s*([\\w+#.-]*)\\s*$")
    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val LETTERED = Regex("^(\\s*)([A-Za-z])[.):]\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    private val BOX_TOP = Regex("┌─+(┬─+)+┐")
    private val BOX_SEP = Regex("├─+(┼─+)+┤")
    private val BOX_BOTTOM = Regex("└─+(┴─+)+┘")

    fun parse(source: String): List<MdBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<MdBlock>()
        val para = ArrayList<String>()
        val listIndents = ArrayList<Int>()
        fun flushPara() {
            if (para.isNotEmpty()) {
                out += MdBlock.Paragraph(parseInline(para.joinToString("\n")))
                para.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flushPara()
                val marker = fence.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !(lines[i].trim().startsWith(marker.take(3)) &&
                        lines[i].trim().all { it == marker[0] })
                ) {
                    body += lines[i]
                    i++
                }
                i++
                out += MdBlock.Code(fence.groupValues[2], body.joinToString("\n"))
                continue
            }
            if (line.isBlank()) { flushPara(); i++; continue }
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                flushPara()
                out += MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2]))
                i++; continue
            }
            if (RULE.matches(line)) { flushPara(); out += MdBlock.Rule; i++; continue }
            if (line.contains('|') && i + 1 < lines.size && lines[i + 1].contains('-') &&
                lines[i + 1].contains('|') && TABLE_SEP.matches(lines[i + 1])
            ) {
                flushPara()
                val header = splitRow(line).map(::parseInline)
                val rows = ArrayList<List<List<InlineRun>>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    rows += splitRow(lines[i]).map(::parseInline)
                    i++
                }
                out += MdBlock.Table(header, rows)
                continue
            }
            val boxTable = parseBoxTable(lines, i)
            if (boxTable != null) {
                flushPara()
                out += boxTable.first
                i = boxTable.second
                continue
            }
            val quote = QUOTE.matchEntire(line)
            if (quote != null) {
                flushPara()
                val body = ArrayList<String>()
                while (i < lines.size) {
                    val q = QUOTE.matchEntire(lines[i]) ?: break
                    body += q.groupValues[1]
                    i++
                }
                out += MdBlock.Quote(parseInline(body.joinToString("\n").trim()))
                continue
            }
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line) ?: LETTERED.matchEntire(line)
            if (bullet != null || numbered != null) {
                flushPara()
                val m = bullet ?: numbered!!
                val indent = m.groupValues[1].replace("\t", "    ").length
                while (listIndents.isNotEmpty() && indent < listIndents.last()) listIndents.removeAt(listIndents.lastIndex)
                if (listIndents.isEmpty() || indent > listIndents.last()) listIndents += indent
                val marker = if (bullet != null) "•" else m.groupValues[2] + "."
                val text = StringBuilder(m.groupValues[3])
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && BULLET.matchEntire(lines[j]) == null &&
                    NUMBERED.matchEntire(lines[j]) == null && LETTERED.matchEntire(lines[j]) == null && lines[j].startsWith(" ") &&
                    FENCE.matchEntire(lines[j]) == null
                ) {
                    text.append('\n').append(lines[j].trim())
                    j++
                }
                out += MdBlock.ListItem(listIndents.lastIndex, marker, parseInline(text.toString()))
                i = j
                continue
            }
            listIndents.clear()
            para += line
            i++
        }
        flushPara()
        return out
    }

    private fun parseBoxTable(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
        val top = lines[start].trim()
        if (!BOX_TOP.matches(top)) return null
        val columns = top.count { it == '┬' } + 1
        val groups = ArrayList<List<List<String>>>()
        var bodySeparated = false
        var group = ArrayList<List<String>>()
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                BOX_BOTTOM.matches(line) -> {
                    if (line.count { it == '┴' } + 1 != columns) return null
                    if (group.isNotEmpty()) groups += group
                    if (groups.isEmpty()) return null
                    fun joinCells(rows: List<List<String>>) = (0 until columns).map { column ->
                        parseInline(rows.map { it[column] }.filter(String::isNotEmpty).joinToString(" "))
                    }
                    val header = joinCells(groups.first())
                    val body = groups.drop(1)
                    val rows = if (bodySeparated) body.map(::joinCells)
                        else body.flatten().map { joinCells(listOf(it)) }
                    return MdBlock.Table(header, rows) to i + 1
                }
                BOX_SEP.matches(line) -> {
                    if (line.count { it == '┼' } + 1 != columns || group.isEmpty()) return null
                    if (groups.isNotEmpty()) bodySeparated = true
                    groups += group
                    group = ArrayList()
                }
                line.startsWith('│') && line.endsWith('│') -> {
                    val cells = line.drop(1).dropLast(1).split('│').map(String::trim)
                    if (cells.size != columns) return null
                    group += cells
                }
                else -> return null
            }
            i++
        }
        return null
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.drop(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
    }

    private val BARE_URL = Regex("https?://[^\\s<>()\\[\\]{}]+")

    fun parseInline(text: String): List<InlineRun> {
        val runs = ArrayList<InlineRun>()
        parseInto(text, InlineRun(""), runs)
        return merge(runs)
    }

    private fun parseInto(s: String, style: InlineRun, out: MutableList<InlineRun>, depth: Int = 0) {
        if (depth > 16) {
            out += style.copy(text = s)
            return
        }
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) { addPlain(buf.toString(), style, out); buf.clear() }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|~>" -> {
                    buf.append(s[i + 1]); i += 2
                }
                c == '`' -> {
                    val ticks = s.substring(i).takeWhile { it == '`' }.length
                    val close = s.indexOf("`".repeat(ticks), i + ticks)
                    if (close > 0) {
                        flush()
                        out += style.copy(text = s.substring(i + ticks, close).trim(), code = true)
                        i = close + ticks
                    } else { buf.append(s, i, i + ticks); i += ticks }
                }
                c == '[' -> {
                    val link = matchLink(s, i)
                    if (link != null) {
                        flush()
                        parseInto(link.label, style.copy(url = link.url), out, depth + 1)
                        i = link.end
                    } else { buf.append(c); i++ }
                }
                c == '*' || c == '_' -> {
                    val n = if (s.startsWith("$c$c", i)) 2 else 1
                    val delim = c.toString().repeat(n)
                    val intraWord = c == '_' && i > 0 && s[i - 1].isLetterOrDigit()
                    val close = if (intraWord) -1 else findClose(s, i + n, delim)
                    val opensOk = i + n < s.length && !s[i + n].isWhitespace()
                    if (close > 0 && opensOk) {
                        flush()
                        val inner = s.substring(i + n, close)
                        parseInto(inner, if (n == 2) style.copy(bold = true) else style.copy(italic = true), out, depth + 1)
                        i = close + n
                    } else { buf.append(s, i, i + n); i += n }
                }
                c == '~' && s.startsWith("~~", i) -> {
                    val close = s.indexOf("~~", i + 2)
                    if (close > 0) {
                        flush()
                        parseInto(s.substring(i + 2, close), style.copy(strike = true), out, depth + 1)
                        i = close + 2
                    } else { buf.append("~~"); i += 2 }
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
    }

    private fun findClose(s: String, from: Int, delim: String): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '\\') { i += 2; continue }
            if (s[i] == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > 0) { i = end + 1; continue }
            }
            if (s.startsWith(delim, i) && i > from && !s[i - 1].isWhitespace() &&
                !(delim.length == 1 && s.startsWith(delim + delim[0], i)) &&
                !(delim[0] == '_' && i + delim.length < s.length && s[i + delim.length].isLetterOrDigit())
            ) return i
            i++
        }
        return -1
    }

    private data class LinkMatch(val label: String, val url: String, val end: Int)

    private fun matchLink(s: String, start: Int): LinkMatch? {
        var depth = 0
        var i = start
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        if (i + 1 >= s.length || s[i + 1] != '(') return null
        val urlEnd = s.indexOf(')', i + 2)
        if (urlEnd < 0) return null
        val url = s.substring(i + 2, urlEnd).trim().substringBefore(' ')
        if (url.isEmpty()) return null
        return LinkMatch(s.substring(start + 1, i), url, urlEnd + 1)
    }

    private fun addPlain(text: String, style: InlineRun, out: MutableList<InlineRun>) {
        if (style.url != null || style.code) { out += style.copy(text = text); return }
        var last = 0
        BARE_URL.findAll(text).forEach { m ->
            val url = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            if (url.length < "https://x".length) return@forEach
            if (m.range.first > last) out += style.copy(text = text.substring(last, m.range.first))
            out += style.copy(text = url, url = url)
            last = m.range.first + url.length
        }
        if (last < text.length) out += style.copy(text = text.substring(last))
    }

    private fun merge(runs: List<InlineRun>): List<InlineRun> {
        val out = ArrayList<InlineRun>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val p = out.lastOrNull()
            if (p != null && p.url == null && p.copy(text = "") == r.copy(text = "")) {
                out[out.lastIndex] = p.copy(text = p.text + r.text)
            } else out += r
        }
        return out
    }
}
