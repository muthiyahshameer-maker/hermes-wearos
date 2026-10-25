package dev.hermes.wearos.domain

import kotlin.math.abs

/**
 * Media-viewer zoom math (walkthrough follow-up: the round watch viewport CLIPS the corners of a
 * fit-scaled image, so the viewer must go BELOW fit). Scale is relative to FIT (1f = the image
 * exactly fitted to the viewport). Floor 0.3×fit, ceiling 6×, double-tap keeps the fit↔2.5×
 * cycle. Pan bounds FOLLOW the scale — [panMax] is the symmetric reach of the overflow in each
 * axis (below fit that is the letterbox slack, which is exactly what lets the owner slide the
 * picture so a clipped corner comes into the round viewport). Pure: unit-tested, no android.
 */
object MediaZoom {
    /** Zoom-out floor: 0.3× fit (sub-fit zoom for round-viewport corner inspection). */
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 6f

    /** Fit = identity scale; the double-tap "max" stop of the fit↔[FIT_MAX] cycle. */
    const val FIT = 1f
    const val FIT_MAX = 2.5f

    /** Dead-zone around fit: within this, taps dismiss and drags stay inert (photo-app feel). */
    const val EPS = 0.05f

    fun clampScale(s: Float): Float =
        if (s.isNaN()) FIT else s.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Near fit (tap-to-dismiss / pan dead-zone test). */
    fun atFit(scale: Float): Boolean = abs(scale - FIT) <= EPS

    /** Pan is meaningful whenever we are NOT at fit — zoomed in OR below fit. */
    fun panEnabled(scale: Float): Boolean = !atFit(scale)

    /**
     * Max |translation| still on-screen at [scale] for a [viewportW]×[viewportH] px viewport.
     * Zoomed in: half the content overflow ((s−1)·vp/2). Below fit: half the letterbox slack
     * ((1−s)·vp/2) — sliding that far parks the image edge on the viewport edge.
     */
    fun panMaxX(scale: Float, viewportW: Int): Float = abs(scale - FIT) * viewportW / 2f
    fun panMaxY(scale: Float, viewportH: Int): Float = abs(scale - FIT) * viewportH / 2f

    /** Keep a proposed translation inside the scale-dependent bounds (never throws on s<1). */
    fun clampOffset(scale: Float, x: Float, y: Float, viewportW: Int, viewportH: Int): FloatArray {
        val mx = panMaxX(scale, viewportW)
        val my = panMaxY(scale, viewportH)
        return floatArrayOf(
            if (x.isNaN()) 0f else x.coerceIn(-mx, mx),
            if (y.isNaN()) 0f else y.coerceIn(-my, my),
        )
    }
}
