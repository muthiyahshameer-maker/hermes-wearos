package dev.hermes.wearos.domain

/**
 * Edit-&-resend math for owner batch 2 ("click on my old prompts … so I can fix stuff"). Desktop
 * edit truncates the server history back to the edited turn and re-runs; the watch CANNOT truncate
 * the gateway's SQLite history, so an edit is APPENDED as a NEW user turn in the SAME session
 * (branch-truncate is a gateway-side follow-up). This helper is the whole rewrap contract and is
 * unit-tested: blank edits are dropped, everything else is trimmed and becomes the outgoing ask.
 */
object PromptEdit {

    /**
     * The text to send when the user edits [original] to [edited], or null when nothing should be
     * sent: whitespace-only edits, and edits identical to the original, are no-ops (the send button
     * stays disabled / the tap is ignored). A real edit returns the trimmed text; it is NOT marked
     * up (no "(edited)" prefix) so the agent sees a clean prompt exactly like a fresh ask.
     */
    fun rewrap(original: String, edited: String): String? {
        val trimmed = edited.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == original.trim()) return null
        return trimmed
    }
}
