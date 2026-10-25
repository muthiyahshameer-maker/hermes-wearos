package dev.hermes.wearos.domain

/**
 * Hands-free listen watchdog (owner follow-up A). Tapping the TALK orb starts a capture that must
 * end itself: Vosk's endpointer carries word timings, but the simplest robust rule is the silence
 * watchdog — **once ≥1 word has been heard, a gap of [SILENCE_MS] with no new partial word
 * finalizes and sends**. [MAX_MS] keeps the mic from hanging open forever (a hands-free session
 * with zero words hits the cap and cancels empty — same as tapping with nothing said).
 *
 * Pure state machine: MainActivity feeds it every partial + a slow tick; unit-tested.
 */
object Vad {
    /** Silence that ends a hands-free capture (owner: "~1.2 s after ≥1 word heard"). */
    const val SILENCE_MS = 1_200L

    /** Hard cap: never listen longer than this, words or not. */
    const val MAX_MS = 20_000L

    data class State(val startedAtMs: Long = 0L, val words: Int = 0, val lastWordAtMs: Long = 0L)

    fun start(nowMs: Long): State = State(startedAtMs = nowMs)

    /**
     * Record a partial. Vosk partials are cumulative for the current utterance, so growth in the
     * word count is "a new word just landed" — that restarts the silence clock.
     */
    fun onPartial(s: State, partialText: String, nowMs: Long): State {
        val n = countWords(partialText)
        return if (n > s.words) s.copy(words = n, lastWordAtMs = nowMs) else s
    }

    fun countWords(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    /** True when the hands-free capture should finalize+send right now. */
    fun shouldFinalize(
        s: State,
        nowMs: Long,
        silenceMs: Long = SILENCE_MS,
        maxMs: Long = MAX_MS,
    ): Boolean {
        if (s.startedAtMs <= 0L) return false
        if (nowMs - s.startedAtMs >= maxMs) return true // empty at the cap → caller sends nothing
        return s.words > 0 && s.lastWordAtMs > 0L && nowMs - s.lastWordAtMs >= silenceMs
    }
}
