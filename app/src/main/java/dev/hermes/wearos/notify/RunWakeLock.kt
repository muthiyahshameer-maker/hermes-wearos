package dev.hermes.wearos.notify

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Background-run keeper (M5): a run that outlives the foreground needs its SSE stream / status
 * polling to survive screen-off. On Wear, doze suspends network for non-foreground apps; the
 * process holding a wakelock is classified ACTIVE by DeviceIdle, which keeps data flowing —
 * without it a backgrounded run just stalls in pollUntilTerminal's catch-loop (observed on the
 * emulator as 15 s connect timeouts on an 18 s cycle).
 *
 * Scope: acquired when we attach to a run, released on settle, auto-times-out at 10 min so a
 * crashed listener can never strand it. (A foreground service is the heavier evolution if
 * runs ever exceed a few minutes — documented tradeoff, SPEC §5 U1.)
 */
object RunWakeLock {
    private const val TAG = "HermesWear"
    private const val TIMEOUT_MS = 10 * 60 * 1000L

    @Volatile private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context, reason: String) {
        if (lock?.isHeld == true) return
        val pm = context.getSystemService(PowerManager::class.java)
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:run").apply {
            setReferenceCounted(false)
            acquire(TIMEOUT_MS)
            Log.i(TAG, "wakelock acquired ($reason)")
        }
    }

    @Synchronized
    fun release(reason: String) {
        lock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "wakelock released ($reason)")
            }
        }
    }
}
