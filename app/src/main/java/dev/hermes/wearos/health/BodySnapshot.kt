package dev.hermes.wearos.health

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * W8 snapshot assembly (SPEC §5 "Body tools"): the watch → watchkit payload. Pure Kotlin so the
 * toggle/staleness logic is unit-testable; BodyTools feeds it live values, SnapshotPusher POSTs
 * the JSON to POST /health/snapshot on watchkit.
 *
 * Owner-locked default: every tool OFF until switched — values for disabled tools never appear
 * in `values` and their tool name never appears in `enabled_tools` (double gate server-side).
 * Staleness (2 h) is enforced by watchkit when serving /health/tool/NAME, and mirrored here so the
 * watch refuses to push a stale cache as if it were fresh.
 */
object BodySnapshot {
    const val TOOL_HR = "watch_heart_rate"
    const val TOOL_STEPS = "watch_steps"
    const val TOOL_SLEEP = "watch_sleep"
    const val TOOL_SPO2 = "watch_spo2"
    const val TOOL_LOC = "watch_location"
    const val STALE_MS = 2 * 60 * 60 * 1000L

    /** Per-tool opt-ins (Settings ▸ Body tools), all default OFF. */
    data class Toggles(
        val heartRate: Boolean = false,
        val steps: Boolean = false,
        val sleep: Boolean = false,
        val spo2: Boolean = false,
        val location: Boolean = false,
    ) {
        fun any(): Boolean = heartRate || steps || sleep || spo2 || location
    }

    /** Latest known values + the instant each was captured (0 = never). */
    data class Values(
        val heartRateBpm: Double? = null,
        val heartRateTs: Long = 0L,
        val stepsToday: Long? = null,
        val stepsTs: Long = 0L,
        val sleepStart: Long? = null,
        val sleepEnd: Long? = null,
        val spo2Pct: Double? = null,
        val spo2Ts: Long = 0L,
        // Owner ask B: one-shot GPS/network fix (LocationTool). Watchkit serves it with a tighter
        // 30-min freshness rule than the other tools — a 2-h-old position is a wrong position.
        val locationLat: Double? = null,
        val locationLng: Double? = null,
        val locationAccuracyM: Double? = null,
        val locationTs: Long = 0L,
    )

    fun enabledTools(t: Toggles): List<String> = buildList {
        if (t.heartRate) add(TOOL_HR)
        if (t.steps) add(TOOL_STEPS)
        if (t.sleep) add(TOOL_SLEEP)
        if (t.spo2) add(TOOL_SPO2)
        if (t.location) add(TOOL_LOC)
    }

    fun isStale(sampleTs: Long, nowMs: Long): Boolean =
        sampleTs <= 0L || nowMs - sampleTs > STALE_MS

    /**
     * Snapshot JSON: {ts, chat_active, enabled_tools, values{...}} — only values whose toggle is
     * ON and that are fresher than [STALE_MS] (vs [now]) are emitted; missing values are omitted.
     */
    fun buildJson(now: Long, chatActive: Boolean, t: Toggles, v: Values): String {
        val values = LinkedHashMap<String, JsonElement>()
        if (t.heartRate && v.heartRateBpm != null && !isStale(v.heartRateTs, now))
            values["heart_rate_bpm"] = JsonPrimitive(v.heartRateBpm)
        if (t.steps && v.stepsToday != null && !isStale(v.stepsTs, now))
            values["steps_today"] = JsonPrimitive(v.stepsToday)
        // Sleep freshness keys on the session END (last night's sleep always "started" >2h ago).
        if (t.sleep && v.sleepStart != null && !isStale(v.sleepEnd ?: v.sleepStart, now)) {
            val sleep = LinkedHashMap<String, JsonElement>()
            val start = v.sleepStart!!
            sleep["last_session_start"] = JsonPrimitive(start)
            val end = v.sleepEnd
            if (end != null) sleep["last_session_end"] = JsonPrimitive(end)
            if (end != null && end > start)
                sleep["duration_hours"] = JsonPrimitive(Math.round((end - start) / 36_000.0) / 100.0)
            values["sleep"] = JsonObject(sleep)
        }
        if (t.spo2 && v.spo2Pct != null && !isStale(v.spo2Ts, now))
            values["spo2_pct"] = JsonPrimitive(v.spo2Pct)
        if (t.location && v.locationLat != null && v.locationLng != null && !isStale(v.locationTs, now)) {
            val loc = LinkedHashMap<String, JsonElement>()
            loc["lat"] = JsonPrimitive(v.locationLat!!)
            loc["lng"] = JsonPrimitive(v.locationLng!!)
            if (v.locationAccuracyM != null) loc["accuracy_m"] = JsonPrimitive(v.locationAccuracyM!!)
            loc["ts"] = JsonPrimitive(v.locationTs)
            values["location"] = JsonObject(loc)
        }
        val obj = LinkedHashMap<String, JsonElement>()
        obj["ts"] = JsonPrimitive(now)
        obj["chat_active"] = JsonPrimitive(chatActive)
        obj["enabled_tools"] = JsonArray(enabledTools(t).map { JsonPrimitive(it) })
        obj["values"] = JsonObject(values)
        return JsonObject(obj).toString()
    }
}
