package dev.hermes.wearos.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM sanity for the markdown-lite parser (SPEC §5 F3/F4): block classification, inline spans,
 * the stream-unsafe cases (unclosed fence, unterminated markers) and the never-lose-text rule.
 */
class WatchMarkdownTest {

    private fun plainSpans(text: String) = listOf(MdSpan.Plain(text))

    @Test fun headingsLevels() {
        val blocks = WatchMarkdown.parse("# One\n## Two\n### Three\n#### Four")
        assertEquals(
            listOf(
                MdBlock.Heading(1, plainSpans("One")),
                MdBlock.Heading(2, plainSpans("Two")),
                MdBlock.Heading(3, plainSpans("Three")),
                MdBlock.Heading(4, plainSpans("Four")),
            ),
            blocks,
        )
    }

    @Test fun inlineBoldItalicCode() {
        val blocks = WatchMarkdown.parse("Some **bold** and *italic* and `inline`.")
        assertEquals(1, blocks.size)
        val p = blocks[0] as MdBlock.Paragraph
        assertEquals(
            listOf(
                MdSpan.Plain("Some "),
                MdSpan.Bold(listOf(MdSpan.Plain("bold"))),
                MdSpan.Plain(" and "),
                MdSpan.Italic(listOf(MdSpan.Plain("italic"))),
                MdSpan.Plain(" and "),
                MdSpan.InlineCode("inline"),
                MdSpan.Plain("."),
            ),
            p.spans,
        )
    }

    @Test fun boldCanNestCode() {
        val p = WatchMarkdown.parse("**run `main()` now**")[0] as MdBlock.Paragraph
        assertEquals(
            listOf(MdSpan.Bold(listOf(
                MdSpan.Plain("run "),
                MdSpan.InlineCode("main()"),
                MdSpan.Plain(" now"),
            ))),
            p.spans,
        )
    }

    @Test fun codeFenceClosedWithLang() {
        val blocks = WatchMarkdown.parse("intro\n\n```kotlin\nfun main() { println(42) }\n```\n\nafter")
        assertTrue(blocks[1] is MdBlock.CodeBlock)
        val cb = blocks[1] as MdBlock.CodeBlock
        assertEquals("kotlin", cb.lang)
        assertEquals("fun main() { println(42) }", cb.code)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    /** Live streaming safety: fence opened but not yet closed ⇒ rest IS the code block. */
    @Test fun unclosedFenceBecomesCodeBlock() {
        val blocks = WatchMarkdown.parse("Here you go:\n\n```python\nprint('hi')\nprin")
        assertEquals(2, blocks.size)
        val cb = blocks[1] as MdBlock.CodeBlock
        assertEquals("python", cb.lang)
        assertEquals("print('hi')\nprin", cb.code)
    }

    @Test fun mermaidFenceSplitOut() {
        val blocks = WatchMarkdown.parse("```mermaid\ngraph TD; A-->B\n```")
        assertEquals(MdBlock.MermaidFence("graph TD; A-->B"), blocks[0])
    }

    // ---- F6 images: image-only lines become Image blocks, mixed lines stay paragraphs ----

    @Test fun imageOnlyLineBecomesImageBlock() {
        val blocks = WatchMarkdown.parse("Look:\n\n![red square](/home/user/.hermes/red.png)\n\nafter")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Image("red square", "/home/user/.hermes/red.png"), blocks[1])
    }

    @Test fun imageEmptyAltAndHttpTarget() {
        val b = WatchMarkdown.parse("![](https://example.com/a.webp)")[0] as MdBlock.Image
        assertEquals("", b.alt)
        assertEquals("https://example.com/a.webp", b.target)
    }

    @Test fun imageOptionalTitleIsDropped() {
        val b = WatchMarkdown.parse("![a](/tmp/x.png \"the title\")")[0] as MdBlock.Image
        assertEquals("/tmp/x.png", b.target)
    }

    @Test fun mixedImageLineStaysParagraph() {
        val blocks = WatchMarkdown.parse("here ![alt](/tmp/x.png) inline")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    /** Stream safety: a half-arrived image line is plain text, never a broken block. */
    @Test fun partialImageLineStaysParagraph() {
        val blocks = WatchMarkdown.parse("![red square](/home/user/.hermes/red.pn")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test fun imageInsideFenceIsNotImageBlock() {
        val blocks = WatchMarkdown.parse("```\n![x](/tmp/a.png)\n```")
        assertTrue(blocks[0] is MdBlock.CodeBlock)
    }

    @Test fun bulletsIndentAndNumbered() {
        val blocks = WatchMarkdown.parse("- first\n    - nested\n\n1. one\n2) two")
        assertEquals(MdBlock.BulletItem(plainSpans("first"), 0), blocks[0])
        assertEquals(MdBlock.BulletItem(plainSpans("nested"), 2), blocks[1])
        assertEquals(MdBlock.NumberedItem(1, plainSpans("one")), blocks[2])
        assertEquals(MdBlock.NumberedItem(2, plainSpans("two")), blocks[3])
    }

    @Test fun quoteBlockStripsMarkers() {
        val q = WatchMarkdown.parse("> a **quote**\n> more")[0] as MdBlock.Quote
        assertEquals(
            listOf(MdSpan.Plain("a "), MdSpan.Bold(listOf(MdSpan.Plain("quote"))), MdSpan.Plain("\nmore")),
            q.spans,
        )
    }

    @Test fun hrLines() {
        val blocks = WatchMarkdown.parse("a\n\n---\n\nb")
        assertTrue(blocks[1] is MdBlock.HrLine)
        assertTrue(WatchMarkdown.parse("***") .first() is MdBlock.HrLine)
    }

    @Test fun tableDegradesToLabelledLines() {
        val t = WatchMarkdown.parse("| lang | year |\n|------|------|\n| kotlin | 2011 |")[0] as MdBlock.Table
        assertEquals(listOf("· lang: kotlin", "· year: 2011"), t.lines)
    }

    /** Never lose text: unterminated markers render as literal characters. */
    @Test fun unparseableInlineStaysPlain() {
        val p = WatchMarkdown.parse("**not closed and `code stuck")[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan.Plain("**not closed and `code stuck")), p.spans)
    }

    @Test fun emojiAndUnicodePassThrough() {
        val p = WatchMarkdown.parse("🎉 你好 — **ok** 🚀")[0] as MdBlock.Paragraph
        assertEquals("🎉 你好 — ", p.spans[0].let { (it as MdSpan.Plain).text })
        assertEquals(listOf(MdSpan.Plain("ok")), (p.spans[1] as MdSpan.Bold).children)
        assertTrue((p.spans[2] as MdSpan.Plain).text.contains("🚀"))
    }

    @Test fun plainTextRoundTripsUnchanged() {
        val text = "Just a normal reply, no markdown at all.\nSecond line."
        val blocks = WatchMarkdown.parse(text)
        assertEquals(1, blocks.size)
        assertEquals(text, WatchMarkdown.parse(text).let { (it[0] as MdBlock.Paragraph).spans.joinToString("") { s -> (s as MdSpan.Plain).text } })
    }
}
