package dev.hermes.wearos.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Round-viewport discipline (owner ask #5) as PURE math. Wear Compose 1.4.1 has no
 * `androidx.wear.compose.foundation.Scaffold`/`ROUND_SCAFFOLD_INSETS` yet (verified against the
 * 1.4.1 artifacts), so the app ships the equivalent itself: these insets feed
 * `Modifier.windowInsetsPadding` / ScalingLazyColumn `contentPadding`, and [arcLiftDp] places the
 * orb-bar's side buttons on the safe-circle arc instead of a square bottom line.
 */
object RoundScreen {
    /** Status/clock zone (TimeText) the content must start below. */
    const val CLOCK_INSET_DP = 30f

    /** Horizontal falloff toward the circular edge (owner: "hPadding = 20dp + edge falloff"). */
    const val EDGE_H_DP = 20f

    /** Chin clip / bottom safe area — buttons and last lines must clear this. */
    const val CHIN_INSET_DP = 16f

    /** Extra margin inside the physical circle when computing the button arc. */
    const val ARC_MARGIN_DP = 10f

    /** Inset box for a screen shape; square screens keep a modest guttter instead. */
    data class Insets(val topDp: Float, val bottomDp: Float, val horizontalDp: Float)

    fun insetsFor(round: Boolean): Insets =
        if (round) Insets(CLOCK_INSET_DP, CHIN_INSET_DP, EDGE_H_DP) else Insets(0f, 8f, 8f)

    /**
     * How far a button at horizontal [offsetDp] from screen centre must rise so the whole orb bar
     * follows the safe circle (Now-in-Edge arc placement) rather than a flat bottom line.
     * [radiusDp] = half the smaller screen dimension; safe radius = radius − [marginDp].
     * 0 when the offset sits on the circle's lowest point; caps at the safe radius for extreme x.
     */
    fun arcLiftDp(radiusDp: Float, offsetDp: Float, marginDp: Float = ARC_MARGIN_DP): Float {
        if (radiusDp <= 0f) return 0f
        val safeR = radiusDp - abs(marginDp)
        if (safeR <= 0f) return 0f
        val x = abs(offsetDp)
        if (x >= safeR) return safeR
        return safeR - sqrt(safeR * safeR - x * x)
    }

    /** Half of the smaller screen dimension, in dp (the round screen's radius). */
    fun screenRadiusDp(minDimensionDp: Float): Float = if (minDimensionDp <= 0f) 0f else minDimensionDp / 2f
}
