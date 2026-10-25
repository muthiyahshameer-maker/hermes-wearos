package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ORB DRAG-UP classifier table (owner ask: drag the talk orb UP = keyboard editor). The orb sees
 * ONE raw pointer stream and must split it: TAP = hands-free toggle, HOLD = press-to-talk,
 * DRAG_UP = open the text editor with the system keyboard (mic never starts).
 *
 * All px+ms: at the emulator's density 2 the dp gates are 40dp→80px up and 30dp→60px drift;
 * longPress timeout pinned at 400ms here (Wear default; the Tracker takes it as a parameter).
 * Down sample lives at (200, 600); a move is (x, y, eventUptimeMillis).
 */
class OrbGestureTest {

    private val longPress = 400L
    private val minUp = 80f    // 40dp @2x
    private val maxDrift = 60f // 30dp @2x
    private val downX = 200f
    private val downY = 600f

    private data class Move(val x: Float, val y: Float, val t: Long)

    private fun verdict(moves: List<Move>, releaseT: Long, downT: Long = 1_000L): OrbGesture.Verdict {
        val tracker = OrbGesture.Tracker(downX, downY, downT, longPress, minUp, maxDrift)
        moves.forEach { tracker.move(it.x, it.y, it.t) }
        return tracker.verdict(releaseT)
    }

    @Test fun `static quick release is a TAP`() {
        assertEquals(OrbGesture.Verdict.TAP, verdict(listOf(Move(200f, 598f, 1_080L)), releaseT = 1_090L))
    }

    @Test fun `jittery quick release stays a TAP`() {
        // Watch touches always drift a few px — tap must survive it (that was the MediaViewer bug).
        assertEquals(OrbGesture.Verdict.TAP, verdict(listOf(Move(205f, 595f, 1_060L)), releaseT = 1_100L))
    }

    @Test fun `press held past the timeout with no movement is a HOLD`() {
        assertEquals(OrbGesture.Verdict.HOLD, verdict(emptyList(), releaseT = 1_700L))
        assertEquals(
            OrbGesture.Verdict.HOLD,
            verdict(listOf(Move(200f, 600f, 1_450L)), releaseT = 1_700L),
        )
    }

    @Test fun `upward slide past the gate is a DRAG_UP`() {
        // Rises 80px (exactly the 40dp gate) with 3px of drift at 150ms — long before timeout.
        assertEquals(OrbGesture.Verdict.DRAG_UP, verdict(listOf(Move(203f, 520f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `DRAG_UP locks the moment it crosses - staying put after does not become HOLD`() {
        assertEquals(
            OrbGesture.Verdict.DRAG_UP,
            verdict(listOf(Move(203f, 520f, 1_150L), Move(200f, 300f, 1_600L)), releaseT = 1_650L),
        )
    }

    @Test fun `HOLD locks first - an upward slide AFTER the timeout stays PTT`() {
        // 40px up at 300ms (not enough), then 120px up at 450ms — hold won the race at 400ms,
        // dragging the finger while talking must not hijack PTT into the editor.
        assertEquals(
            OrbGesture.Verdict.HOLD,
            verdict(listOf(Move(200f, 560f, 1_300L), Move(200f, 480f, 1_450L)), releaseT = 1_700L),
        )
    }

    @Test fun `rise under the gate (30dp) is still a TAP`() {
        assertEquals(OrbGesture.Verdict.TAP, verdict(listOf(Move(200f, 530f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `steep diagonal - rise ok but drift past 30dp - is a TAP not DRAG_UP`() {
        assertEquals(OrbGesture.Verdict.TAP, verdict(listOf(Move(270f, 500f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `drift exactly on the 30dp gate still counts as DRAG_UP`() {
        assertEquals(OrbGesture.Verdict.DRAG_UP, verdict(listOf(Move(260f, 500f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `downward drag (scroll intent) released early is a TAP`() {
        // Only UP opens the editor; a downward flick on the orb keeps the old tap-with-slop semantics.
        assertEquals(OrbGesture.Verdict.TAP, verdict(listOf(Move(200f, 700f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `crossing exactly on the timeout event is a HOLD (hold wins the tie)`() {
        assertEquals(OrbGesture.Verdict.HOLD, verdict(listOf(Move(200f, 500f, 1_400L)), releaseT = 1_420L))
    }

    @Test fun `dp gate constants match the owner spec`() {
        assertEquals(40f, OrbGesture.MIN_UP_DP, 0.0001f)
        assertEquals(30f, OrbGesture.MAX_DRIFT_DP, 0.0001f)
    }
}
