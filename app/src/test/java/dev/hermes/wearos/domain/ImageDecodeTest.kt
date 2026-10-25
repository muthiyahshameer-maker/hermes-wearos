package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner batch 2: full-res images reach the watch, and the watch picks the BitmapFactory
 * inSampleSize — pure rule, so it is tested without a device.
 */
class ImageDecodeTest {

    @Test fun smallSourceDecodesWhole() {
        // Source already below target → sample 1 (never upscale, never sample down).
        assertEquals(1, ImageDecode.sampleSize(400, 300, 512))
    }

    @Test fun exactBoundaryKeepsPowerOfTwo() {
        // 1024 long side, target 512: sample 2 lands EXACTLY on target (>= target).
        assertEquals(2, ImageDecode.sampleSize(1024, 768, 512))
    }

    @Test fun hugePhotoSamplesToJustAboveTarget() {
        // 4000px phone photo, watch card target 512: 4000/8=500 < 512 → stop at 4 (1000px).
        val s = ImageDecode.sampleSize(4000, 3000, 512)
        assertEquals(4, s)
        // Decoded long side always >= target and < 2×target (crisp-but-small contract).
        val decoded = 4000 / s
        assert(decoded >= 512 && decoded < 1024) { "decoded $decoded outside [512,1024)" }
    }

    @Test fun fullResViewerTargetIsNoSampling() {
        // Viewer passes target ≤ long side ⇒ 1 (every original pixel decoded).
        assertEquals(1, ImageDecode.sampleSize(1600, 1200, 1600))
        assertEquals(1, ImageDecode.sampleSize(1600, 1200, 4000))
    }

    @Test fun garbageBoundsFallBackToWholeDecode() {
        assertEquals(1, ImageDecode.sampleSize(0, 0, 512))
        assertEquals(1, ImageDecode.sampleSize(-5, 100, 512))
        assertEquals(1, ImageDecode.sampleSize(1000, 800, 0))
    }

    @Test fun powerOfTwoAlways() {
        // BitmapFactory honors powers of two; the helper must never emit 3/5/7…
        for (side in intArrayOf(1024, 2048, 4096, 8192, 12000)) {
            val s = ImageDecode.sampleSize(side, side / 2, 512)
            assertEquals("not a power of two: $s", 1, Integer.bitCount(s))
        }
    }
}
