package dev.hermes.wearos.health

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W8: snapshot assembly must honor the per-tool owner switches + the 2 h staleness gate. */
class BodySnapshotTest {
    private val now = 1_760_000_000_000L
    private val fresh = now - 60_000L
    private val stale = now - BodySnapshot.STALE_MS - 1_000L

    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun jsonPrim(o: JsonObject, key: String) = (o[key] as JsonPrimitive).content

    private val values = BodySnapshot.Values(
        heartRateBpm = 67.0, heartRateTs = fresh,
        stepsToday = 3200L, stepsTs = fresh,
        sleepStart = now - 9 * 3_600_000L, sleepEnd = now - 1 * 3_600_000L,
        spo2Pct = 97.0, spo2Ts = fresh,
        locationLat = 49.2827, locationLng = -123.1207, locationAccuracyM = 12.0, locationTs = fresh,
    )

    @Test fun `location gated by its toggle and staleness like the rest`() {
        val off = parse(BodySnapshot.buildJson(now, false, BodySnapshot.Toggles(), values))
        assertEquals(null, off["values"]!!.jsonObject["location"])
        val on = parse(BodySnapshot.buildJson(now, false, BodySnapshot.Toggles(location = true), values))
        assertTrue(on["enabled_tools"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("watch_location"))
        val loc = on["values"]!!.jsonObject["location"]!!.jsonObject
        assertEquals("49.2827", jsonPrim(loc, "lat"))
        assertEquals("-123.1207", jsonPrim(loc, "lng"))
        assertEquals("12.0", jsonPrim(loc, "accuracy_m"))
        val staleLoc = values.copy(locationTs = now - 3 * 3_600_000L) // > 2h app-side gate
        val s = parse(BodySnapshot.buildJson(now, false, BodySnapshot.Toggles(location = true), staleLoc))
        assertEquals(null, s["values"]!!.jsonObject["location"])
    }

    @Test fun `location only omits accuracy when unknown`() {
        val v = values.copy(locationAccuracyM = null)
        val o = parse(BodySnapshot.buildJson(now, false, BodySnapshot.Toggles(location = true), v))
        val loc = o["values"]!!.jsonObject["location"]!!.jsonObject
        assertEquals(null, loc["accuracy_m"])
        assertEquals("49.2827", jsonPrim(loc, "lat"))
    }

    @Test fun `all off emits no values and no tools`() {
        val o = parse(BodySnapshot.buildJson(now, false, BodySnapshot.Toggles(), values))
        assertEquals(0, o["values"]!!.jsonObject.size)
        assertEquals(0, o["enabled_tools"]!!.jsonArray.size)
    }

    @Test fun `toggles gate both enabled_tools and values`() {
        val t = BodySnapshot.Toggles(heartRate = true, spo2 = true)
        val o = parse(BodySnapshot.buildJson(now, true, t, values))
        assertEquals(listOf("watch_heart_rate", "watch_spo2"),
            o["enabled_tools"]!!.jsonArray.map { it.jsonPrimitive.content })
        val v = o["values"]!!.jsonObject
        assertEquals("67.0", jsonPrim(v, "heart_rate_bpm"))
        assertEquals("97.0", jsonPrim(v, "spo2_pct"))
        assertFalse("steps toggle off must drop steps_today", v.containsKey("steps_today"))
        assertFalse("sleep toggle off must drop sleep", v.containsKey("sleep"))
        assertTrue(jsonPrim(o, "chat_active").toBoolean())
    }

    @Test fun `stale samples are dropped from the snapshot`() {
        val t = BodySnapshot.Toggles(heartRate = true, steps = true)
        val v = values.copy(heartRateTs = stale) // only HR stale
        val obj = parse(BodySnapshot.buildJson(now, false, t, v))["values"]!!.jsonObject
        assertFalse(obj.containsKey("heart_rate_bpm"))
        assertTrue(obj.containsKey("steps_today"))
    }

    @Test fun `sleep block carries window and duration hours`() {
        val t = BodySnapshot.Toggles(sleep = true)
        val sleep = parse(BodySnapshot.buildJson(now, false, t, values))["values"]!!
            .jsonObject["sleep"]!!.jsonObject
        assertEquals("8.0", jsonPrim(sleep, "duration_hours"))
        assertTrue(jsonPrim(sleep, "last_session_start").toLong() < jsonPrim(sleep, "last_session_end").toLong())
    }

    @Test fun `sleep ending over 2h ago is stale and dropped`() {
        val t = BodySnapshot.Toggles(sleep = true)
        val v = values.copy(sleepStart = now - 12 * 3_600_000L, sleepEnd = now - BodySnapshot.STALE_MS - 60_000L)
        val vals = parse(BodySnapshot.buildJson(now, false, t, v))["values"]!!.jsonObject
        assertFalse(vals.containsKey("sleep"))
    }

    @Test fun `staleness boundary - exactly 2h is fresh, over is stale, never-seen is stale`() {
        assertFalse(BodySnapshot.isStale(now - BodySnapshot.STALE_MS, now))
        assertTrue(BodySnapshot.isStale(now - BodySnapshot.STALE_MS - 1, now))
        assertTrue(BodySnapshot.isStale(0L, now))
    }

    @Test fun `enabled tools list matches switch matrix`() {
        assertEquals(
            listOf(BodySnapshot.TOOL_HR, BodySnapshot.TOOL_STEPS, BodySnapshot.TOOL_SLEEP, BodySnapshot.TOOL_SPO2),
            BodySnapshot.enabledTools(BodySnapshot.Toggles(true, true, true, true)),
        )
        assertEquals(emptyList<String>(), BodySnapshot.enabledTools(BodySnapshot.Toggles()))
    }
}
