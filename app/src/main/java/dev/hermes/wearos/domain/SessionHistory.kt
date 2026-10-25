package dev.hermes.wearos.domain

import dev.hermes.wearos.net.HermesApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure adapter for watchkit `GET /session_messages` (see server/watchkit) — the transcript
 * backfill that makes an ADOPTED gateway session open with its history instead of empty
 * (owner bug: computer sessions opened blank on adopt). The endpoint already drops tool rows
 * and inactive content; this layer owns the app-side truth: role mapping to [ChatMessage],
 * residual noise stripping, the newest-[cap] window, the caption line, and the two rules —
 * [shouldBackfill] (never clobber a locally-owned transcript) and [profileProbes] (whose
 * state.db to knock on when the adopted id's profile is unknown or wrong).
 * No android imports — unit-tested.
 */
object SessionHistory {

    /** Same cap the endpoint clamps to: ~120 newest turns on a watch transcript. */
    const val MAX_MESSAGES = 120

    /** Server caps at 2 KB/msg; this is the belt-and-suspenders trim for older watchkits. */
    const val MAX_TEXT = 2000

    /** Compression-summary rows render as one assistant line with this marker. */
    const val SUMMARY_PREFIX = "🗂 summary · "

    /**
     * Parse `{messages:[{role,content,ts}]}` → chat rows, oldest-first. Roles: "user"→User,
     * "assistant"/"summary"→Hermes (summaries prefixed), anything else dropped; blank text and
     * background-process noise dropped; over [MAX_TEXT] trimmed. When more than [cap] rows come
     * back the NEWEST [cap] are kept (a long desktop chat opens on its tail). Malformed JSON
     * throws — the caller fail-softs the whole backfill.
     */
    fun parse(text: String, cap: Int = MAX_MESSAGES): List<ChatMessage> {
        val arr = (HermesApi.parse(text)["messages"] as? JsonArray) ?: return emptyList()
        val rows = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val role = (o["role"] as? JsonPrimitive?)?.contentOrNull ?: return@mapNotNull null
            val raw = (o["content"] as? JsonPrimitive?)?.contentOrNull ?: return@mapNotNull null
            val body = raw.trim()
            if (body.isEmpty() || isNoise(body)) return@mapNotNull null
            val mapped = when (role) {
                "user" -> ChatMessage.Role.User
                "summary" -> ChatMessage.Role.Hermes
                "assistant" -> ChatMessage.Role.Hermes
                else -> return@mapNotNull null
            }
            val full = if (role == "summary") SUMMARY_PREFIX + body else body
            ChatMessage(0L, mapped, if (full.length > MAX_TEXT) full.take(MAX_TEXT) + "…" else full)
        }
        return rows.takeLast(cap.coerceAtLeast(0))
    }

    /** Residual noise the endpoint can still leak: injected process notifications, bare markers. */
    private fun isNoise(body: String): Boolean =
        body.startsWith("[IMPORTANT: Background process") ||
            body.startsWith("[SYSTEM: Background process") ||
            body == "—"

    /** Cheap-context caption at the top of a backfilled transcript. */
    fun captionLine(count: Int, profile: String): String =
        "history · $count messages · from ${ProfileLogic.chipLabel(profile)}"

    /**
     * BACKFILL RULE (owner): a chat only stands on its LOCAL transcript — backfill exactly when
     * the chat has no local rows (fresh adoption, an older adoption whose transcript was empty,
     * or a session created elsewhere: curl, another device). A ⌚ watch-created chat gains rows
     * with its first message, so its transcript is never empty and always survives untouched.
     */
    fun shouldBackfill(sessionId: String, localTranscriptSize: Int): Boolean =
        sessionId.isNotBlank() && localTranscriptSize == 0

    /**
     * Profiles to try for [sessionId], in order: [primary] first, then the computer home —
     * session rows live in their profile's state.db and a dev adoption (or a stale chip tag)
     * may not know the real one. Deduped; `default` last since it's the biggest store.
     */
    fun profileProbes(primary: String): List<String> =
        listOf(primary.trim().ifEmpty { ProfileLogic.PROFILE_COMPUTER }, ProfileLogic.PROFILE_COMPUTER).distinct()
}
