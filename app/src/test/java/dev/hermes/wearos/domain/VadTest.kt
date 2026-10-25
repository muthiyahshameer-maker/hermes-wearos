package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Follow-up A: the hands-free tap-listen watchdog (words + silence → send; cap → cancel). */
class VadTest {
    private val t0 = 1_000_000L

    @Test fun `nothing heard never finalizes before the hard cap`() {
        var s = Vad.start(t0)
        assertFalse(Vad.shouldFinalize(s, t0 + 5_000))
        assertTrue(Vad.shouldFinalize(s, t0 + Vad.MAX_MS)) // cap: empty session must end (→ cancel)
    }

    @Test fun `words reset the silence clock and silence past the gap sends`() {
        var s = Vad.start(t0)
        s = Vad.onPartial(s, "hello", t0 + 400)
        assertFalse(Vad.shouldFinalize(s, t0 + 1_200)) // 800 ms of silence: keep listening
        s = Vad.onPartial(s, "hello world", t0 + 1_200) // new word at 1.2s
        assertFalse(Vad.shouldFinalize(s, t0 + 2_000)) // only 800 ms quiet since "world"
        assertTrue(Vad.shouldFinalize(s, t0 + 1_200 + Vad.SILENCE_MS)) // 1.2 s after the last word
    }

    @Test fun `same repeated partial is not a new word`() {
        var s = Vad.start(t0)
        s = Vad.onPartial(s, "hey", t0 + 300)
        val again = Vad.onPartial(s, "hey", t0 + 900)
        assertEquals(s.lastWordAtMs, again.lastWordAtMs) // clock NOT reset
        assertTrue(Vad.shouldFinalize(again, t0 + 300 + Vad.SILENCE_MS))
    }

    @Test fun `word count is whitespace-tolerant and monotonic max wins`() {
        var s = Vad.start(t0)
        s = Vad.onPartial(s, "  one   two\tthree ", t0 + 100)
        assertEquals(3, s.words)
        s = Vad.onPartial(s, "one", t0 + 200) // Vosk shrink glitch: never lowers the count
        assertEquals(3, s.words)
        assertEquals(0, Vad.countWords("   "))
    }

    @Test fun `unstarted state never fires`() {
        assertFalse(Vad.shouldFinalize(Vad.State(), t0 + 60_000))
    }

    @Test fun `owner pace - one two three counted with pauses stays open then sends`() {
        var s = Vad.start(t0)
        s = Vad.onPartial(s, "one", t0 + 500)
        s = Vad.onPartial(s, "one two", t0 + 1_500)
        s = Vad.onPartial(s, "one two three", t0 + 2_500)
        assertFalse(Vad.shouldFinalize(s, t0 + 3_000))
        assertTrue(Vad.shouldFinalize(s, t0 + 2_500 + Vad.SILENCE_MS + 1))
        assertEquals(3, s.words)
    }
}
