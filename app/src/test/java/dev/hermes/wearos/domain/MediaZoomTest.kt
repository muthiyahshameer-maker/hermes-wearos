package dev.hermes.wearos.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media-viewer sub-fit zoom floor + scale-following pan bounds. */
class MediaZoomTest {

    @Test fun `zoom floor sits below fit - 0_3x allowed`() {
        assertEquals(MediaZoom.MIN_SCALE, MediaZoom.clampScale(0.05f), 0.0001f)
        assertEquals(0.5f, MediaZoom.clampScale(0.5f), 0.0001f) // BELOW the old fit floor: legal now
        assertEquals(2.5f, MediaZoom.clampScale(2.5f), 0.0001f)
        assertEquals(MediaZoom.MAX_SCALE, MediaZoom.clampScale(99f), 0.0001f)
        assertEquals(MediaZoom.FIT, MediaZoom.clampScale(Float.NaN), 0.0001f)
    }

    @Test fun `double-tap cycle stays fit to 2_5x within bounds`() {
        assertEquals(2.5f, MediaZoom.FIT_MAX, 0.0001f)
        assertTrue(MediaZoom.FIT_MAX in MediaZoom.MIN_SCALE..MediaZoom.MAX_SCALE)
    }

    @Test fun `fit dead-zone covers tap-dismiss and pan enablement`() {
        assertTrue(MediaZoom.atFit(1f))
        assertTrue(MediaZoom.atFit(1.04f))
        assertFalse(MediaZoom.atFit(0.9f))
        assertFalse(MediaZoom.atFit(2.5f))
        assertFalse(MediaZoom.panEnabled(1f))
        assertTrue("sub-fit MUST allow panning (clipped-corner inspection)", MediaZoom.panEnabled(0.4f))
        assertTrue(MediaZoom.panEnabled(2.5f))
    }

    @Test fun `pan bounds follow the scale - zoomed in`() {
        assertEquals(0f, MediaZoom.panMaxX(1f, 600), 0.001f)
        assertEquals(450f, MediaZoom.panMaxX(2.5f, 600), 0.001f) // (2.5-1)*600/2
        assertEquals(360f, MediaZoom.panMaxY(2.5f, 480), 0.001f)
    }

    @Test fun `pan bounds follow the scale - below fit letterbox slack`() {
        assertEquals(100f, MediaZoom.panMaxX(0.5f, 400), 0.001f) // (1-0.5)*400/2
        assertEquals(168f, MediaZoom.panMaxY(0.3f, 480), 0.001f) // floor scale, full letterbox
    }

    @Test fun `clamp offset never throws below fit - the old coerceIn crash`() {
        // s<1 used to make maxX negative → coerceIn(-neg, neg) IllegalArgumentException.
        val o = MediaZoom.clampOffset(0.5f, 500f, -500f, 400, 400)
        assertArrayEquals(floatArrayOf(100f, -100f), o, 0.001f)
        val atFit = MediaZoom.clampOffset(1f, 30f, -30f, 400, 400)
        assertArrayEquals(floatArrayOf(0f, 0f), atFit, 0.001f)
        val nan = MediaZoom.clampOffset(0.5f, Float.NaN, Float.NaN, 400, 400)
        assertArrayEquals(floatArrayOf(0f, 0f), nan, 0.001f)
    }
}
