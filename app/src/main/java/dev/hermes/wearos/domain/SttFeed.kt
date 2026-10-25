package dev.hermes.wearos.domain

/**
 * Pure line-stacker for the listening overlay (owner walkthrough: "I don't see the live stt, font
 * way too big … show at least the last few sentences on screen"). The old design rendered the
 * whole cumulative partial centered at title2 — on a 480×480 round face it clipped off-frame.
 * New design: a bottom-anchored STACK of at most [MAX_LINES] short lines — finalized segments
 * first, then the live cumulative partial split at sentence boundaries, newest LAST (rendered at
 * the bottom of the safe circle). No android.* imports — unit-tested.
 */
object SttFeed {
    /** Lines on screen incl. the live one (owner: "at least the last few sentences"). */
    const val MAX_LINES = 3

    /** Hard wrap target for wordless run-ons (px-free char budget; ~2 lines of body1 at the edges). */
    private const val CHUNK_CHARS = 64

    /**
     * Final overlay content, oldest → newest (index last = the freshest, biggest text).
     * [partial] is Vosk's CUMULATIVE in-utterance text; [history] holds finalized segments
     * (oldest first) from earlier utterances of the same listening session.
     */
    fun lines(partial: String, history: List<String>, maxLines: Int = MAX_LINES): List<String> {
        val out = history.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        out += sentences(partial)
        return out.filter { it.isNotEmpty() }.takeLast(maxLines.coerceAtLeast(1))
    }

    /**
     * Split cumulative STT text into display chunks: cut after `.` `?` `!` (keeping the mark),
     * and hard-wrap a wordless run-on past [CHUNK_CHARS] at word boundaries so the last three
     * lines are always sentences/fragments near what was JUST said, never one giant block.
     */
    fun sentences(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        // Split keeping terminators; the trailing terminatorless tail is the live utterance.
        val chunks = Regex("[^.!?\\n]+[.!?]?").findAll(t).map { it.value.trim() }.toMutableList()
        if (chunks.isEmpty()) chunks.add(t)
        return chunks.flatMap { wrap(it) }.filter { it.isNotEmpty() }
    }

    /** Word-boundary wrap of oversized chunks (a 300-char run-on becomes readable ladder steps). */
    private fun wrap(chunk: String): List<String> {
        if (chunk.length <= CHUNK_CHARS) return listOf(chunk)
        val out = mutableListOf<String>()
        var rest = chunk
        while (rest.length > CHUNK_CHARS) {
            var cut = rest.lastIndexOf(' ', CHUNK_CHARS)
            if (cut <= 0) cut = CHUNK_CHARS
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
