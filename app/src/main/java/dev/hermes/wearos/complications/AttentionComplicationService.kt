package dev.hermes.wearos.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.hermes.wearos.MainActivity
import dev.hermes.wearos.notify.Attention

/**
 * "Hermes attention" complication (SPEC §5 U4): a bold "!" (watch-face-tinted) when any chat has a settled
 * answer or approval waiting that the user hasn't seen, a dim dot otherwise. The user places it
 * via the watch-face editor (hold-space / ranked slot).
 *
 * SHORT_TEXT with a text glyph instead of an icon asset: renders identically small, works in
 * ambient, and keeps the app icon-free for this feature. Data comes from [Attention] (prefs
 * written in-process + pushed with requestUpdateAll on change) — no ContentProvider needed,
 * because this service runs in the app's own process. If the watch-face never asks us, the
 * complication simply shows whatever data was last served (system caches it).
 */
class AttentionComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        if (request.complicationType == ComplicationType.SHORT_TEXT) data(Attention.read(this)) else null

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) data(true) else null

    private fun data(attention: Boolean): ComplicationData =
        ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(if (attention) "!" else "•").build(),
            PlainComplicationText.Builder(if (attention) "Hermes needs attention" else "Hermes").build(),
        )
            .setTapAction(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
}
