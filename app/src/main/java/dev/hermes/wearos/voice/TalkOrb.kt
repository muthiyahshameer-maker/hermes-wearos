package dev.hermes.wearos.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hermes.wearos.R
import dev.hermes.wearos.domain.OrbGesture
import kotlinx.coroutines.withTimeoutOrNull

// LAYOUT D recolor (owner-approved): the disc is the launcher-dark #0D1117 with the white Hermes
// glyph instead of olive/gold; pressed BRIGHTENS the slate so the hold is visible on the dark disc.
private val OrbIdle = Color(0xFF0D1117)
private val OrbPressed = Color(0xFF3A4A66)
private val OrbLive = Color(0xFFB3261E)
private val OrbStop = Color(0xFF7F1D1D)
private val OrbStopRing = Color(0xFFFF5252)

/**
 * The TALK orb, OWNER BATCH 2 redesign: "talk doesn't need to be a full circle; talk button maybe
 * can actually be the hermes icon" — a circular button rendering the HERMES MASCOT glyph
 * (ic_hermes_orb, a tintable white mark; NO "TALK" text anymore) on the launcher-dark disc.
 * LAYOUT D: the diameter is now a PARAMETER (the bottom cluster feeds the computed circle), the
 * default keeps the old 68 dp look for anything else that embeds it.
 *  - idle: the glyph (slightly softened alpha).
 *  - pressed: glyph brightened to full white + lighter base (visual feedback for the hold).
 *  - listening: red base + PULSE RING (expanding/fading circle), glyph kept.
 *  - running (a reply streams): red ring + a STOP square over the dimmed glyph — tap/hold-release
 *    stops the run (ask #3 semantics UNCHANGED).
 * Tap = hands-free listening (VAD auto-send), hold = push-to-talk, exactly as before: the gesture
 * is hand-rolled (after the long-press timeout with the finger still down we enter hold mode and
 * call onHoldStart; the eventual up/cancel calls onHoldEnd). Not a Material `Button`: wear
 * Buttons swallow click-only semantics and the hold gesture needs the raw pointer stream.
 */
@Composable
fun TalkOrb(
    listening: Boolean,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    diameter: Dp = 68.dp,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    // ORB DRAG-UP (owner ask): release after an UPWARD drag (≥40dp, drift <30dp) crossing
    // BEFORE the long-press timeout → keyboard editor instead of mic. Never fires tap/hold.
    onDragUp: () -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        running -> OrbStop
        listening -> OrbLive
        pressed -> OrbPressed
        else -> OrbIdle
    }
    val pulse = rememberInfiniteTransition(label = "orbpulse")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "t")

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(diameter)) {
        // Pulse ring (listening only): an expanding, fading red circle OUTSIDE the orb — drawn
        // first so the orb always covers its center.
        if (listening && !running) {
            Box(
                modifier = Modifier
                    .size(diameter)
                    .graphicsLayer {
                        val s = 1f + 0.45f * t
                        scaleX = s; scaleY = s
                        alpha = 1f - t
                    }
                    .border(2.dp, OrbStopRing.copy(alpha = 0.9f), CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .then(
                    if (running) Modifier.border(androidx.compose.foundation.BorderStroke(3.dp, OrbStopRing), CircleShape).padding(3.dp)
                    else Modifier
                )
                .clip(CircleShape)
                .background(bg)
                .pointerInput(running) {
                    val minUp = OrbGesture.MIN_UP_DP.dp
                    val maxDrift = OrbGesture.MAX_DRIFT_DP.dp
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        val tracker = OrbGesture.Tracker(
                            down.position.x, down.position.y, down.uptimeMillis,
                            viewConfiguration.longPressTimeoutMillis, minUp.toPx(), maxDrift.toPx(),
                        )
                        var dragArmed = false
                        // TRUE = released before the long-press timeout; FALSE = drag gate crossed;
                        // null = timeout expired (hold/PTT territory).
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed }
                                if (ch == null) return@withTimeoutOrNull true // up (or cancel)
                                // This artifact's PointerEvent has no event timestamp — the
                                // per-change uptimeMillis is the time source (1.7-era API).
                                tracker.move(ch.position.x, ch.position.y, ch.uptimeMillis)
                                if (tracker.dragUpCrossed) {
                                    dragArmed = true
                                    // Claim the stream: the transcript under the orb must not scroll.
                                    ev.changes.forEach { if (it.pressed) it.consume() }
                                    return@withTimeoutOrNull false
                                }
                            }
                        }
                        when {
                            dragArmed -> {
                                // DRAG-UP: wait out the finger, then hand off to the editor — no
                                // hands-free listening, no PTT (the mic never started).
                                android.util.Log.d("HermesWear", "orb: drag-up -> editor")
                                waitForUpOrCancellation()
                                pressed = false
                                onDragUp()
                            }
                            up != null -> {
                                pressed = false
                                onTap()
                            }
                            running -> {
                                // STOP state: a hold is just a slow tap — release stops the run.
                                waitForUpOrCancellation()
                                pressed = false
                                onTap()
                            }
                            else -> {
                                onHoldStart()
                                waitForUpOrCancellation()
                                pressed = false
                                onHoldEnd()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The hermes mark: white tintable glyph. Idle/pressed = bright white (pressed FULL +
            // lighter base); listening = white on red; running = dimmed under the STOP square.
            Image(
                painter = painterResource(R.drawable.ic_hermes_orb),
                contentDescription = if (running) "stop" else "talk with hermes",
                contentScale = ContentScale.Fit,
                colorFilter = when {
                    running -> ColorFilter.tint(Color.White.copy(alpha = 0.25f))
                    listening -> ColorFilter.tint(Color.White.copy(alpha = 0.55f))
                    pressed -> ColorFilter.tint(Color.White)
                    else -> ColorFilter.tint(Color.White.copy(alpha = 0.92f))
                },
                modifier = Modifier.size(diameter * 0.76f),
            )
            // STOP overlay glyph (running): white rounded square, semantics unchanged (ask #3).
            if (running) {
                Box(
                    modifier = Modifier
                        .size(diameter * 0.32f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White),
                )
            }
        }
    }
}
