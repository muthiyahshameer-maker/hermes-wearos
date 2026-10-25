package dev.hermes.wearos.domain

/**
 * Pure decode math for owner batch 2 ("images should be in full res to the watch; scaling should
 * happen on the watch"): the watch fetches ORIGINAL bytes and picks a BitmapFactory `inSampleSize`
 * per use — a small one for the chat card, 1 for the pinch-zoom viewer. No android.* imports so
 * the sampling rule is unit-tested.
 */
object ImageDecode {

    /**
     * Largest power-of-two `inSampleSize` whose decoded output is still AT LEAST [targetMaxPx] on
     * the long side — the BitmapFactory idiom: never below target (card stays crisp), never above
     * 2× target (memory stays ~bounded), 1 for small sources. Garbage inputs → 1 (decode as-is).
     */
    fun sampleSize(srcW: Int, srcH: Int, targetMaxPx: Int): Int {
        if (srcW <= 0 || srcH <= 0 || targetMaxPx <= 0) return 1
        var sample = 1
        while (maxOf(srcW, srcH) / (sample * 2) >= targetMaxPx) sample *= 2
        return sample
    }
}
