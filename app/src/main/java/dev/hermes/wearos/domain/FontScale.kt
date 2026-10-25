package dev.hermes.wearos.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PINCH FONT-SCALE math (owner ask: two-finger pinch in the message area scales TEXT/MARKDOWN
 * type, NOT a visual zoom — image/mermaid cards keep their footprint). Pure clamp+quantize on a
 * 0.05 grid between 0.75× and 1.6×; the gesture feeds raw `calculateZoom()` deltas multiplicatively.
 * Values persist through AppSettings; NaN/garbage always land on a valid step. No compose imports.
 */
object FontScale {
    const val MIN = 0.75f
    const val MAX = 1.6f
    const val STEP = 0.05f
    const val DEFAULT = 1f

    /** Steps per unit (1/STEP) — integer grid keeps quantization drift-free. */
    private const val PER_UNIT = 20f

    fun clamp(v: Float): Float = if (v.isNaN()) DEFAULT else v.coerceIn(MIN, MAX)

    /** Snap to the 0.05 grid, then clamp (a snap past the ends lands exactly on MIN/MAX). */
    fun quantize(v: Float): Float {
        if (v.isNaN() || v.isInfinite()) return DEFAULT
        return clamp(v).let { (it * PER_UNIT).roundToInt() / PER_UNIT }
    }

    /** Pinch apply: current × gesture zoom factor → snapped, clamped scale. */
    fun apply(current: Float, zoomFactor: Float): Float =
        if (zoomFactor.isNaN() || zoomFactor <= 0f) quantize(current)
        else quantize(clamp(current) * zoomFactor)

    /** Double-tap on empty transcript resets to 1.0 (the hidden reset gesture). */
    fun isDefault(v: Float): Boolean = abs(quantize(clamp(v)) - DEFAULT) < STEP / 2f
}
