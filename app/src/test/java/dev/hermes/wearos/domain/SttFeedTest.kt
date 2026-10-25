package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Overlay line-stacker (walkthrough readability): the newest words must ALWAYS be among the
 * rendered lines, the stack never exceeds the frame budget, and run-ons ladder-step instead of
 * clipping — the "I don't see the live stt" fix, pure side.
 */
class SttFeedTest {

    @Test fun `empty speech yields no lines and the overlay shows its placeholder`() {
        assertEquals(emptyList<String>(), SttFeed.lines("", emptyList()))
        assertEquals(emptyList<String>(), SttFeed.lines("   ", listOf("", "  ")))
    }

    @Test fun `a single live sentence is the only (newest) line`() {
        assertEquals(
            listOf("what's the weather today"),
            SttFeed.lines("what's the weather today", emptyList()),
        )
    }

    @Test fun `cumulative partial splits at sentence marks newest stays last`() {
        val lines = SttFeed.lines(
            "turn on the lights. what time is it? any replies from the desk agent!",
            emptyList(),
        )
        assertEquals(
            listOf("turn on the lights.", "what time is it?", "any replies from the desk agent!"),
            lines,
        )
    }

    @Test fun `stack keeps only the last three lines and always ends with the freshest text`() {
        val partial = "one. two. three. four. five"
        val lines = SttFeed.lines(partial, emptyList())
        assertEquals(3, lines.size)
        assertEquals(listOf("three.", "four.", "five"), lines)
    }

    @Test fun `finalized segments stack under the live partial (PTT continues after each final)`() {
        val lines = SttFeed.lines(
            "and the third request",
            listOf("first request done.", "second request done."),
        )
        assertEquals(
            listOf("first request done.", "second request done.", "and the third request"),
            lines,
        )
    }

    @Test fun `old history falls off as new speech arrives`() {
        val lines = SttFeed.lines("now.", listOf("oldest.", "middle.", "recent."))
        assertEquals(listOf("middle.", "recent.", "now."), lines)
    }

    @Test fun `wordless run-on wraps at word boundaries into ladder steps`() {
        val long = "list " + (1..40).joinToString(" ") { "item$it" }
        val lines = SttFeed.lines(long, emptyList())
        assertEquals(3, lines.size)
        assertTrue("live tail must be the last line", lines.last().contains("item40"))
        assertTrue("lines stay frame-sized", lines.all { it.length <= 64 })
    }

    @Test fun `maxLines stays sane for hostile inputs`() {
        assertEquals(1, SttFeed.lines("hi", emptyList(), maxLines = 1).size)
        assertEquals(1, SttFeed.lines("a. b.", emptyList(), maxLines = 0).size) // coerced to ≥1
        assertEquals(listOf("b."), SttFeed.lines("a. b.", emptyList(), maxLines = 0))
    }
}
