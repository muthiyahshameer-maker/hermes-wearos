package dev.hermes.wearos.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Location tool (owner ask B): the watch knowing where it is. Deliberately android.location only —
 * no Play Services dependency joins this APK for one tool. API 30 gives us the one-shot
 * `getCurrentLocation(provider, …)`: last-known first, then a fresh single fix capped at ~10 s.
 * Everything is fail-soft like BodyTools — permissions off / providers off / no fix (emulator!)
 * degrade to a status string, never an exception path.
 *
 * Feeds [dev.hermes.wearos.health.BodySnapshot] `values.location` (only while the Settings toggle
 * is ON); watchkit serves it as `watch_location` with a 30-min freshness cap.
 */
object LocationTool {
    private const val TAG = "HermesWear"
    const val FIX_TIMEOUT_MS = 10_000L

    /** A position + when it was taken. */
    data class Fix(val lat: Double, val lng: Double, val accuracyM: Double, val tsMs: Long)

    @Volatile
    private var lastFix: Fix? = null
    private val _status = MutableStateFlow("not probed yet")
    val status: StateFlow<String> = _status.asStateFlow()

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Providers the user currently has ON (gps / network / passive), best-first. */
    fun availableProviders(context: Context): List<String> {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return emptyList()
        return try { lm.allProviders.filter { lm.isProviderEnabled(it) } } catch (e: Exception) { emptyList() }
    }

    /** Cheap cache read for cadence pushes — never blocks, may be null (Settings/dev show why). */
    fun cachedFix(): Fix? = lastFix

    /** Last fix the OS still holds (no probe). Null when none/no permission. */
    fun lastKnown(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val loc = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
                    ?: continue
                return Fix(loc.latitude, loc.longitude, loc.accuracy.toDouble(), loc.time)
            }
        } catch (e: Exception) { Log.w(TAG, "lastKnown failed: ${e.message}") }
        return null
    }

    /**
     * Blocking one-shot fix (call off the main thread): OS last-known first, then a real fix via
     * [LocationManager.getCurrentLocation] bounded by [timeoutMs]. Updates [status] fail-soft;
     * returns the freshest fix available or null — the pusher simply omits `location` when null.
     */
    fun probe(context: Context, timeoutMs: Long = FIX_TIMEOUT_MS): Fix? {
        if (!hasPermission(context)) {
            _status.value = "denied: location permission not granted (adb pm grant or Settings)"
            return lastFix
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: run { _status.value = "unavailable: no LocationManager"; return lastFix }
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } }
            ?: availableProviders(context).firstOrNull()
            ?: run {
                _status.value = "providers off: enable Location (gps+network)"
                return lastFix ?: lastKnown(context)
            }
        val known = lastKnown(context)
        val cancel = CancellationSignal()
        val latch = CountDownLatch(1)
        var fresh: Fix? = null
        try {
            // API 30 overload (minSdk here): provider string + cancellation + executor callback.
            lm.getCurrentLocation(provider, cancel, Executors.newSingleThreadExecutor()) { loc ->
                if (loc != null) {
                    fresh = Fix(loc.latitude, loc.longitude, loc.accuracy.toDouble(), loc.time)
                }
                latch.countDown()
            }
            val got = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!got) cancel.cancel()
        } catch (e: Exception) {
            _status.value = "probe failed: ${e.message ?: e.javaClass.simpleName}"
            return known.also { if (it != null) lastFix = it }
        }
        val fix = fresh ?: known
        return if (fix != null) {
            lastFix = fix
            _status.value = "fix ok · ${formatFix(fix, System.currentTimeMillis())} via $provider"
            fix
        } else {
            _status.value = "no fix in ${timeoutMs / 1000}s via $provider (indoor/GPS cold? emulator has no fix)"
            null
        }
    }

    /** Logcat/dev line for `-e loc_status`: perms + providers + last outcome. */
    fun statusLine(context: Context): String {
        val perms = if (hasPermission(context)) "granted" else "MISSING"
        val providers = availableProviders(context).ifEmpty { listOf("none") }.joinToString(",")
        return "perms=$perms providers=$providers status='${status.value}' lastFix=${
            lastFix?.let { formatFix(it, System.currentTimeMillis()) } ?: "none"
        }"
    }

    /** One human line for a fix. */
    fun formatFix(f: Fix, nowMs: Long): String =
        "lat=%.5f lng=%.5f acc=%.0fm age=%ds".format(f.lat, f.lng, f.accuracyM, maxOf((nowMs - f.tsMs) / 1000, 0))
}
