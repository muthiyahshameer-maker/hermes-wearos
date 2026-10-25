package dev.hermes.wearos.domain

import kotlin.math.abs

/**
 * ORB GESTURE classification (owner ask: press-and-DRAG the talk orb UP → keyboard editor).
 * The orb's gesture lane must tell three intents apart from one raw pointer stream:
 *
 *  - DRAG_UP: the pointer rose ≥ [MIN_UP_DP] with lateral drift under [MAX_DRIFT_DP] AND the
 *    crossing happened BEFORE the long-press timeout — mic never starts, editor opens instead.
 *  - HOLD: the long-press timeout wins first (hold-to-talk, unchanged). Once hold is locked,
 *    an upward slide that crosses later stays a HOLD (PTT while talking is natural).
 *  - TAP: released before the timeout without crossing the drag gate — incl. small upward
 *    slides (<40) and steep diagonals (drift ≥30), exactly the old tap-with-slop semantics.
 *
 * The TalkOrb detector drives this Tracker per pointer event (drag gate) and fires the hold on
 * its own timeout; [Tracker.verdict] classifies a COMPLETED gesture from the same rules, which
 * is what the table-driven unit tests exercise. Pure: px + ms in, verdict out — no android.
 */
object OrbGesture {
    /** Upward travel (dp) from the DOWN point that arms the editor gesture. */
    const val MIN_UP_DP = 40f

    /** Max horizontal deviation (dp) from the down x for the drag to count as "vertical". */
    const val MAX_DRIFT_DP = 30f

    enum class Verdict { TAP, HOLD, DRAG_UP }

    /** Incremental classifier fed with (x, y, eventUptimeMillis) moves after the DOWN. */
    class Tracker(
        private val downX: Float,
        private val downY: Float,
        private val downT: Long,
        private val longPressMs: Long,
        private val minUpPx: Float,
        private val maxDriftPx: Float,
    ) {
        /** True from the moment an up-slide crossed the gate before the hold locked. */
        var dragUpCrossed = false
            private set

        /** True once the long-press timeout passed without a drag crossing (hold wins). */
        var holdFired = false
            private set

        /** Feed one pointer event (post-DOWN moves; DOWN itself may be replayed — harmless). */
        fun move(x: Float, y: Float, tMs: Long) {
            if (dragUpCrossed || holdFired) return
            if (tMs - downT >= longPressMs) {
                holdFired = true
                return
            }
            if (downY - y >= minUpPx && abs(x - downX) <= maxDriftPx) dragUpCrossed = true
        }

        /** Classification once the pointer lifted at [releaseT] (or the hold ran on). */
        fun verdict(releaseT: Long): Verdict = when {
            dragUpCrossed -> Verdict.DRAG_UP
            holdFired || releaseT - downT >= longPressMs -> Verdict.HOLD
            else -> Verdict.TAP
        }
    }
}
