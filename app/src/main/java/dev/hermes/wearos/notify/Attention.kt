package dev.hermes.wearos.notify

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.hermes.wearos.complications.AttentionComplicationService

/**
 * Single source of truth for the attention bit (SPEC §5 U4): any chat with `pendingAttention`
 * (settled-while-backgrounded or approval waiting). Written to a SharedPreferences file that
 * [AttentionComplicationService] reads, then the complication is refreshed via
 * ComplicationDataSourceUpdateRequester.requestUpdateAll() — no background worker, no FCM.
 *
 * Deliberately provider-less (task's "simplest acceptable"): the DynamicComplicationService
 * runs *inside our process*, so plain MODE_PRIVATE prefs are shared without MODE_MULTI_PROCESS.
 * Caveats documented where they bite:
 *  - Wear caches complication data per watch-face slot; requestUpdateAll nudges a re-fetch.
 *  - Only fires when the bit flips (spamming requestUpdateAll wastes battery).
 *  - On emulators without the complication editor wired, the service still binds on request
 *    (verified via the ScheduleUpdate log + dumpsys listing).
 */
object Attention {
    private const val TAG = "HermesWear"
    const val PREFS = "hermes_attention"
    const val KEY_ATTENTION = "attention"

    @Volatile private var lastPublished: Boolean? = null

    fun publish(app: Context, attention: Boolean) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ATTENTION, attention).apply()
        if (lastPublished == attention) return
        lastPublished = attention
        try {
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, AttentionComplicationService::class.java))
                .requestUpdateAll()
            Log.i(TAG, "complication ScheduleUpdate requested attention=$attention")
        } catch (e: Exception) {
            Log.w(TAG, "complication update request failed: ${e.message}")
        }
    }

    fun read(app: Context): Boolean =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ATTENTION, false)
}
