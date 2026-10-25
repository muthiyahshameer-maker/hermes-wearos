package dev.hermes.wearos.ui.md

/**
 * Inline fragments produced by [WatchMarkdown]. Leaf [MdSpan.Plain] always keeps the original
 * characters: anything we cannot parse (unterminated `**`, stray `` ` ``, …) degrades to literal
 * text, never to dropped text. Unicode/emoji pass through untouched.
 */
sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class Bold(val children: List<MdSpan>) : MdSpan
    data class Italic(val children: List<MdSpan>) : MdSpan
    data class InlineCode(val text: String) : MdSpan
}

/** Block-level nodes (SPEC §5 F3). One node ≈ one composable row on the watch list. */
sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class BulletItem(val spans: List<MdSpan>, val indent: Int) : MdBlock
    data class NumberedItem(val n: Int, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class CodeBlock(val lang: String, val code: String) : MdBlock
    data class MermaidFence(val code: String) : MdBlock

    /**
     * F6 inline image: an image-ONLY line `![alt](target)`. target is an absolute gateway-host
     * path (routed through watchkit /img) or an http(s) URL (fetched direct). Deliberate
     * simplification: a line that MIXES an image with other text stays a plain paragraph —
     * bitmaps inside running text are unreadable at 480px round, and the alt text still says
     * what it was. (Commented per SPEC §5 F6.)
     */
    data class Image(val alt: String, val target: String) : MdBlock

    /**
     * Markdown tables degrade to pre-formatted "· col: cell" caption lines — the watch is too
     * narrow for a real grid, so each non-blank cell becomes its own labelled line (F3 note).
     */
    data class Table(val lines: List<String>) : MdBlock
    data object HrLine : MdBlock
}

/**
 * Parse-then-render markdown-lite (no third-party renderer: `horologist-markdown` does not exist
 * as a resolvable artifact — 404 on Google maven + Maven Central; heavier libs like markwon pull
 * Android parsing stacks that misbehave on round small screens).
 *
 * Block pass: fences first (``` / ~~~, mermaid split out), then heading / hr / quote / bullet /
 * numbered / table-ish lines; anything else accumulates into paragraphs. Inline pass: **bold**,
 * *italic*, `code` (recursive; bold may contain italic/code).
 *
 * Stream-safe by construction: an unclosed fence consumes the rest of the text as a code block,
 * and unterminated inline markers stay literal — a partially arrived buffer never renders as
 * broken spans or a backtick storm.
 *
 * Pure Kotlin (no Android imports) so app/src/test runs it on the JVM.
 */
object WatchMarkdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val TRAILING_HASHES = Regex("\\s+#+\\s*$")
    private val HR = Regex("^\\s{0,3}(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d{1,9})[.)]\\s+(.*)$")
    // Image-only line; optional "title"/'title' after the path is dropped. No spaces in target
    // (gateway paths are plain absolute paths); anything fancier stays literal paragraph text.
    private val IMAGE_LINE = Regex("^\\s*!\\[([^\\]]*)\\]\\(\\s*([^()\\s]+)(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
        val out = ArrayList<MdBlock>()
        val para = ArrayList<String>()

        fun flushPara() {
            if (para.isEmpty()) return
            val spans = ArrayList<MdSpan>()
            para.forEachIndexed { idx, l ->
                if (idx > 0) spans += MdSpan.Plain("\n")
                spans += parseInline(l)
            }
            para.clear()
            if (spans.any { it !is MdSpan.Plain || it.text.isNotEmpty() }) out += MdBlock.Paragraph(spans)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()

            if (t.isEmpty()) { flushPara(); i++; continue }

            // Fenced code first: ```lang … ``` (unclosed fence = everything left, stream-safe).
            if ((t.startsWith("```") || t.startsWith("~~~")) && !isSeparator(t)) {
                flushPara()
                val fc = t[0]
                val flen = t.takeWhile { it == fc }.length
                val info = t.drop(flen).trim()
                val code = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    val lt = lines[j].trim()
                    if (lt.length >= flen && lt.all { it == fc }) { closed = true; break }
                    code.appendLine(lines[j]); j++
                }
                val body = code.toString().removeSuffix("\n")
                out += if (info.lowercase().startsWith("mermaid")) MdBlock.MermaidFence(body)
                else MdBlock.CodeBlock(info, body)
                i = if (closed) j + 1 else j
                continue
            }

            val hm = HEADING.matchEntire(line)
            if (hm != null) {
                flushPara()
                val txt = hm.groupValues[2].trim().replace(TRAILING_HASHES, "")
                out += MdBlock.Heading(hm.groupValues[1].length, parseInline(txt))
                i++; continue
            }

            if (HR.matches(t)) { flushPara(); out += MdBlock.HrLine; i++; continue }

            if (QUOTE.matches(line)) {
                flushPara()
                val inner = ArrayList<String>()
                var j = i
                while (j < lines.size && QUOTE.matches(lines[j])) {
                    inner += QUOTE.matchEntire(lines[j])!!.groupValues[1]; j++
                }
                out += MdBlock.Quote(parseInline(inner.joinToString("\n")))
                i = j; continue
            }

            val bm = BULLET.matchEntire(line)
            if (bm != null) {
                flushPara()
                val indent = (bm.groupValues[1].length / 2).coerceAtMost(3)
                out += MdBlock.BulletItem(parseInline(bm.groupValues[3]), indent)
                i++; continue
            }

            val nm = NUMBERED.matchEntire(line)
            if (nm != null) {
                flushPara()
                out += MdBlock.NumberedItem(nm.groupValues[1].toInt().coerceAtMost(999), parseInline(nm.groupValues[2]))
                i++; continue
            }

            if (looksLikeTableRow(line)) {
                flushPara()
                val rows = ArrayList<Pair<List<String>, Boolean>>() // cells to isSeparator
                var j = i
                while (j < lines.size && looksLikeTableRow(lines[j])) {
                    rows += cellsOf(lines[j]) to isSeparator(lines[j]); j++
                }
                val data = rows.filter { !it.second }
                val hasHeader = rows.getOrNull(1)?.second == true && data.isNotEmpty()
                val header = if (hasHeader) data.first().first else emptyList()
                val body = if (hasHeader) data.drop(1) else data
                val linesOut = ArrayList<String>()
                body.forEach { (row, _) ->
                    row.forEachIndexed { c, cell ->
                        if (cell.isEmpty()) return@forEachIndexed
                        val label = header.getOrNull(c)?.takeIf { it.isNotEmpty() }
                        linesOut += if (label != null) "· $label: $cell" else "· $cell"
                    }
                }
                // Header-only table (streaming just the first rows): show it unlabeled, not lost.
                if (linesOut.isEmpty() && header.isNotEmpty()) header.forEach { if (it.isNotEmpty()) linesOut += "· $it" }
                if (linesOut.isNotEmpty()) out += MdBlock.Table(linesOut)
                i = j; continue
            }

            // Image-only line → dedicated block (F6). Checked late so fences/headings/bullets
            // win first; mixed `text ![alt](x)` lines fall through to paragraphs on purpose
            // (see MdBlock.Image) and a stream-partial `![a](/pat` stays plain text too.
            val im = IMAGE_LINE.matchEntire(line)
            if (im != null) {
                flushPara()
                out += MdBlock.Image(im.groupValues[1].trim(), im.groupValues[2].trim())
                i++; continue
            }

            para += line; i++
        }
        flushPara()
        return out
    }

    // ---- table heuristics (see MdBlock.Table doc: deliberate, commented degradation) ----

    private fun looksLikeTableRow(line: String): Boolean {
        val t = line.trim()
        if (!t.contains('|')) return false
        return t.startsWith('|') || t.count { it == '|' } >= 2
    }

    private fun isSeparator(t: String): Boolean =
        t.contains('-') && t.all { it == '-' || it == ':' || it == '|' || it == ' ' }

    private fun cellsOf(line: String): List<String> =
        line.trim().trimStart('|').trimEnd('|').split('|').map { it.trim() }

    // ---- inline pass: **bold**, *italic*, `code`; anything unterminated stays literal ----

    internal fun parseInline(text: String): List<MdSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = ArrayList<MdSpan>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) { spans += MdSpan.Plain(buf.toString()); buf.setLength(0) }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush(); spans += MdSpan.InlineCode(text.substring(i + 1, close)); i = close + 1; continue
                }
                buf.append(c); i++; continue // lone backtick: literal
            }
            if (c == '*' && text.startsWith("**", i)) {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    flush(); spans += MdSpan.Bold(parseInline(text.substring(i + 2, close))); i = close + 2; continue
                }
                buf.append("**"); i += 2; continue // unclosed: keep the stars
            }
            if (c == '*' && i + 1 < text.length && text[i + 1] != '*' && !text[i + 1].isWhitespace()) {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1 && close + 1 < text.length && text[close + 1] != '*' && !text[close - 1].isWhitespace()) {
                    flush(); spans += MdSpan.Italic(parseInline(text.substring(i + 1, close))); i = close + 1; continue
                }
                buf.append(c); i++; continue
            }
            buf.append(c); i++
        }
        flush()
        // Coalesce neighbouring Plain spans so tests/renderers see minimal structure.
        val merged = ArrayList<MdSpan>(spans.size)
        for (s in spans) {
            val last = merged.lastOrNull()
            if (s is MdSpan.Plain && last is MdSpan.Plain) merged[merged.size - 1] = MdSpan.Plain(last.text + s.text)
            else merged += s
        }
        return merged
    }
}
