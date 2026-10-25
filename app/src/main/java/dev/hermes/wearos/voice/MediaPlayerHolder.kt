package dev.hermes.wearos.voice

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * Media3 ExoPlayer singleton (M2 stub): plays gateway audio clips (TTS/notify audio in M3+).
 * Minimal by design; voice prompts route through SpeakAloud until the gateway ships audio URLs.
 */
object MediaPlayerHolder {
    private const val TAG = "HermesWear"
    private var player: ExoPlayer? = null

    fun play(context: Context, url: String) {
        try {
            val p = player ?: ExoPlayer.Builder(context.applicationContext).build().also { player = it }
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            Log.i(TAG, "media play $url")
        } catch (e: Exception) {
            Log.e(TAG, "media play failed", e)
        }
    }

    fun stop() {
        player?.stop()
    }

    fun release() {
        player?.release()
        player = null
    }
}
