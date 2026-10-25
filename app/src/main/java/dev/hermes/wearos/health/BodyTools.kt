package dev.hermes.wearos.health

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.awaitWithException
import androidx.health.services.client.data.AggregateDataType
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.CumulativeDataPoint
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.getCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * W1–W4 (SPEC §5 "Body tools") — the Health Services client wrapper. Data source in 2026 is
 * `androidx.health:health-services-client`: MeasureClient live streams (heart rate) +
 * PassiveMonitoringClient batched passive updates (steps totals, vendor body-sensor types).
 * There is NO historical `ReadClient` in any published version — the watch caches what streams
 * in while it's alive and pushes snapshots; sleep/SpO2 exposure is device-gated (Samsung Health,
 * SPEC §10-2) and probed opportunistically by data-type NAME so unknown vendor types surface
 * without a recompile.
 *
 * Everything is fail-soft: no Health Services app (emulator), denied permissions, or a missing
 * data type degrades to `unavailable + reason` in [status] — never a crash. Grant permissions
 * with scripts/watch-health.sh grant (no permission UI in this slice).
 */
object BodyTools {
    private const val TAG = "HermesWear"

    /** Per-tool availability line for Settings/`body_status`: tool → "state · reason". */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    @Volatile private var initialized = false
    @Volatile private var measureRegistered = false
    @Volatile private var passiveRegistered = false
    private var client: HealthServicesClient? = null
    private var appCtx: Context? = null

    // Latest-known cache (arrival-time ts; snapshot push stamps freshness from here).
    @Volatile private var hr: Pair<Double, Long>? = null
    @Volatile private var steps: Pair<Long, Long>? = null
    @Volatile private var spo2: Pair<Double, Long>? = null
    @Volatile private var sleepSession: SleepWindow? = null // last sleep window seen (null = never)

    /** A detected sleep session (start/end ms + arrival ts) from a vendor SLEEP_* data type. */
    data class SleepWindow(val start: Long, val end: Long, val arrival: Long)

    /** Vendor/extra types matched by name so sleep/SpO2 surface when a device ever exposes them. */
    private var spo2Type: DataType<*, *>? = null
    private var sleepType: DataType<*, *>? = null
    // Vendor-discovered HR/steps objects (Samsung names differ from the androidx constants).
    private var hrTypeRef: DataType<*, *>? = null
    private var stepsTypeRef: DataType<*, *>? = null

    fun init(app: Context, scope: CoroutineScope) {
        if (initialized) return
        initialized = true
        appCtx = app.applicationContext
        // Probe+register off the main thread; failures only ever touch [status].
        scope.launch { probe() }
    }

    /**
     * (Re)probe capabilities and register callbacks. Returns the human status map (also exposed
     * via [status]). Safe to call any time; used by `body_status`, Push-now, and the 15-min pusher.
     */
    suspend fun probe(): Map<String, String> {
        val ctx = appCtx ?: return mapOf("health" to "unavailable · BodyTools not initialized")
        val out = LinkedHashMap<String, String>()
        val c = try {
            client ?: HealthServices.getClient(ctx).also { client = it }
        } catch (e: Exception) {
            Log.w(TAG, "bodytools: no HealthServices client: ${e.message}")
            null
        }
        if (c == null) {
            out[BodySnapshot.TOOL_HR] = "unavailable · Health Services app not present"
            out[BodySnapshot.TOOL_STEPS] = out.getValue(BodySnapshot.TOOL_HR)
            out[BodySnapshot.TOOL_SLEEP] = out.getValue(BodySnapshot.TOOL_HR)
            out[BodySnapshot.TOOL_SPO2] = out.getValue(BodySnapshot.TOOL_HR)
            _status.value = out
            return out
        }
        var measureOk = false; var passiveOk = false
        // Keep the real DataType objects: vendor platforms (Samsung on Wear OS 6) expose
        // "HeartRate"/"Steps" names that never string-match the androidx constants — register
        // whatever object the platform actually advertised instead of guessing canonical ones.
        var measureObjs: Set<DataType<*, *>> = emptySet(); var passiveObjs: Set<DataType<*, *>> = emptySet()
        try { c.measureClient.getCapabilities().let {
            measureOk = true; measureObjs = it.supportedDataTypesMeasure.toSet() } }
        catch (e: Exception) { out["measure-cap"] = "unavailable · ${e.message}" }
        try { c.passiveMonitoringClient.getCapabilitiesAsync().awaitWithException().let {
            passiveOk = true; passiveObjs = it.supportedDataTypesPassiveMonitoring.toSet() } }
        catch (e: Exception) { out["passive-cap"] = "unavailable · ${e.message}" }
        val allObjs = measureObjs + passiveObjs
        val hrType = allObjs.firstOrNull { matchesType(it, "heartrate") } ?: DataType.HEART_RATE_BPM
        hrTypeRef = hrType
        fun typed(vararg toks: String) = allObjs.firstOrNull { matchesType(it, *toks) }
        spo2Type = typed("oxygen", "spo2")
        sleepType = typed("sleep")
        stepsTypeRef = typed("steps")

        if (!measureRegistered) {
            try {
                // Measure API only takes Delta types; Samsung's advertised HR object is one.
                val mt = hrType as? DeltaDataType<*, *> ?: DataType.HEART_RATE_BPM
                c.measureClient.registerMeasureCallback(mt, measureCallback)
                measureRegistered = true
            } catch (e: Exception) { out["hr-register"] = "failed · ${e.message}" }
        }
        if (!passiveRegistered) {
            try {
                val wanted = buildSet {
                    passiveObjs.firstOrNull { matchesType(it, "heartrate") }?.let { add(it) }
                    stepsTypeRef?.let { add(it) }
                    spo2Type?.let { add(it) }
                    sleepType?.let { add(it) }
                }
                if (wanted.isNotEmpty()) {
                    val cfg = PassiveListenerConfig.Builder().setDataTypes(wanted).build()
                    c.passiveMonitoringClient.setPassiveListenerCallback(cfg, passiveCallback)
                    passiveRegistered = true
                }
            } catch (e: Exception) { out["passive-register"] = "failed · ${e.message}" }
        }

        out[BodySnapshot.TOOL_HR] = when {
            allObjs.any { matchesType(it, "heartrate") } ->
                "available · measure=$measureRegistered passive=$passiveRegistered" + (hr?.let { " · latest=${it.first.toInt()}bpm @${it.second}" } ?: "")
            else -> "unavailable · no heart-rate data type (measure=$measureOk passive=$passiveOk)"
        }
        out[BodySnapshot.TOOL_STEPS] = when {
            allObjs.any { matchesType(it, "steps") } ->
                "available · passive=$passiveRegistered" + (steps?.let { " · today=${it.first} @${it.second}" } ?: "")
            else -> "unavailable · no STEPS data type (passive=$passiveOk)"
        }
        out[BodySnapshot.TOOL_SLEEP] = if (sleepType != null)
            "available · vendor type $sleepType" + (sleepSession?.let { " · latest @${it.arrival}" } ?: "")
        else "unavailable · SLEEP_SESSION not exposed here (Samsung Health gate — SPEC §10-2, verify at M4)"
        out[BodySnapshot.TOOL_SPO2] = if (spo2Type != null)
            "available · vendor type $spo2Type" + (spo2?.let { " · latest=${it.first} @${it.second}" } ?: "")
        else "unavailable · OXYGEN_SATURATION not exposed here (verify at M4)"
        _status.value = out
        out.forEach { (k, v) -> Log.i(TAG, "bodytools status: $k -> $v") }
        return out
    }

    /** Latest known values for [BodySnapshot.buildJson]. */
    fun values(): BodySnapshot.Values = BodySnapshot.Values(
        heartRateBpm = hr?.first, heartRateTs = hr?.second ?: 0L,
        stepsToday = steps?.first, stepsTs = steps?.second ?: 0L,
        sleepStart = sleepSession?.start, sleepEnd = sleepSession?.end,
        spo2Pct = spo2?.first, spo2Ts = spo2?.second ?: 0L,
    )

    /** Chat asked (SPEC W8 freshness): best-effort re-probe so the paired push isn't blind. */
    fun refresh(scope: CoroutineScope) { scope.launch { probe() } }

    /** Latest-known cache update from any source (measure callback, passive service). */
    fun ingest(container: DataPointContainer, source: String) {
        val now = System.currentTimeMillis()
        try {
            // Constant first; vendor-named twin (Samsung "HeartRate") via generic path.
            val hrV = runCatching { container.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value }
                .getOrNull()
                ?: hrTypeRef?.takeIf { it != DataType.HEART_RATE_BPM }
                    ?.let { ingestGeneric(container, it) }?.first
            hrV?.let { v -> hr = v to now; Log.d(TAG, "bodytools hr=$v ($source)") }
            val stepsV = runCatching { container.getData(DataType.STEPS_TOTAL) }
                .getOrNull()?.total?.toDouble()
                ?: stepsTypeRef?.takeIf { it != DataType.STEPS_TOTAL }
                    ?.let { ingestGeneric(container, it) }?.first
            stepsV?.let { total ->
                // Day-boundary aware: a total whose window starts today is "steps today";
                // a wider window is reported as-is (monotonic device counter).
                steps = total.toLong() to now
                Log.d(TAG, "bodytools steps=$total ($source)")
            }
            spo2Type?.let { dt -> ingestGeneric(container, dt)?.let { spo2 = it; Log.d(TAG, "bodytools spo2=${it.first} ($source)") } }
            sleepType?.let { dt -> ingestGeneric(container, dt)?.let {
                val end = now
                // Value is minutes in bed on devices that expose sleep; window = minutes before now.
                sleepSession = SleepWindow((end - it.first * 60_000).toLong().coerceAtMost(end), end, now)
                Log.d(TAG, "bodytools sleep value=${it.first} ($source)")
            } }
        } catch (e: Exception) {
            Log.w(TAG, "bodytools ingest failed ($source): ${e.message}")
        }
    }

    /** Value+ts for an unknown-but-name-matched data type (SpO2 %/sleep minutes heuristics). */
    private fun ingestGeneric(container: DataPointContainer, dt: DataType<*, *>): Pair<Double, Long>? = try {
        val now = System.currentTimeMillis()
        @Suppress("UNCHECKED_CAST")
        when (dt) {
            is DeltaDataType<*, *> -> container.getData(dt as DeltaDataType<Any, DataPoint<Any>>)
                .lastOrNull()?.let { dp ->
                    val v = when (dp) {
                        is SampleDataPoint<*> -> dp.value as? Number
                        is IntervalDataPoint<*> -> dp.value as? Number
                        else -> null
                    }
                    v?.toDouble()?.let { it to now }
                }
            else -> (container.getData(
                @Suppress("UNCHECKED_CAST") (dt as androidx.health.services.client.data.AggregateDataType<Number, CumulativeDataPoint<Number>>)
            ))?.total?.toDouble()?.let { it to now }
        }
    } catch (e: Exception) { null }

    /**
     * Vendor-tolerant DataType match: strips the toString noise (`DataType(name=HeartRate,…)`)
     * down to letters/digits, lowercases, and substring-tests tokens ("heartrate" hits both the
     * androidx HEART_RATE_BPM constant and Samsung's "HeartRate").
     */
    private fun matchesType(dt: DataType<*, *>, vararg tokens: String): Boolean {
        val n = dt.toString().lowercase().filter { it.isLetterOrDigit() }
        return tokens.any { n.contains(it) }
    }

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
            Log.d(TAG, "bodytools availability $dataType -> $availability")
        }
        override fun onDataReceived(dataPoints: DataPointContainer) = ingest(dataPoints, "measure")
    }

    private val passiveCallback = object : PassiveListenerCallback {
        override fun onRegistrationFailed(error: Throwable) {
            passiveRegistered = false
            Log.w(TAG, "bodytools passive registration failed: ${error.message}")
        }
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) = ingest(dataPoints, "passive")
        override fun onPermissionLost() {
            passiveRegistered = false
            Log.w(TAG, "bodytools passive permission lost — re-grant via watch-health.sh grant")
        }
    }
}
