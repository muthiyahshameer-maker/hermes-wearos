package dev.hermes.wearos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.hermes.wearos.voice.MediaPlayerHolder
import dev.hermes.wearos.voice.VoskStt

/** Debug builds only: `adb shell am broadcast -n .../DevAskReceiver -e text "..."` (or -e set_token K,
 *  -e set_watchkit K, -e render_test, -e img_test, -e chat_new, -e open_chat <id>, -e chat_dump,
 *  -e body_status, -e body_push). UI-epic additions: -e agents_list, -e agent <name>,
 *  -e sessions_dump, -e resume_session <id> [-e resume_profile <p>], -e stop_mid_run [text], -e loc_status,
 *  -e ptt_demo <partial|''> (forces the PTT overlay for screenshots),
 *  -e fontscale <1.4|reset> (persist+apply pinch font-scale without fingers),
 *  -e viewer_demo [scale] (open the media viewer on a generated card — sub-fit zoom proof;
 *  scale like 0.5 opens BELOW fit directly, 0/false closes).
 *  Gateway-epic additions: -e gw_dump, -e gw_probe, -e gw_select <id>. */
class DevAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val token = intent?.getStringExtra("set_token")
        if (token != null) {
            WearApp.Graph.setToken(token)
            return
        }
        intent?.getStringExtra("set_base")?.let { WearApp.Graph.setBase(it); return }
        intent?.getStringExtra("set_watchkit_base")?.let { WearApp.Graph.setWatchkitBase(it); return }
        intent?.getStringExtra("set_watchkit")?.let { WearApp.Graph.setWatchkitToken(it); return }
        if (intent?.hasExtra("gw_dump") == true) { WearApp.Graph.devGwDump(); return }
        if (intent?.hasExtra("gw_probe") == true) { WearApp.Graph.devGwProbe(); return }
        intent?.getStringExtra("gw_select")?.let { WearApp.Graph.devGwSelect(it); return }
        if (intent?.hasExtra("render_test") == true) { WearApp.Graph.devRenderTest(); return }
        if (intent?.hasExtra("img_test") == true) { WearApp.Graph.devImgTest(); return }
        if (intent?.hasExtra("body_status") == true) { WearApp.Graph.devBodyStatus(); return }
        if (intent?.hasExtra("body_push") == true) { WearApp.Graph.devBodyPush(); return }
        if (intent?.hasExtra("loc_status") == true) { WearApp.Graph.devLocStatus(); return }
        if (intent?.hasExtra("agents_list") == true) { WearApp.Graph.devAgentsList(); return }
        if (intent?.hasExtra("sessions_dump") == true) { WearApp.Graph.devSessionsDump(); return }
        intent?.getStringExtra("agent")?.let { WearApp.Graph.devAgent(it); return }
        intent?.getStringExtra("resume_session")?.let {
            WearApp.Graph.devResumeSession(it, intent.getStringExtra("resume_profile").orEmpty()); return
        }
        if (intent?.hasExtra("ptt_demo") == true) {
            // "" clears the forced overlay; any other value shows it with that partial.
            WearApp.Graph.devPtt.value = intent.getStringExtra("ptt_demo").orEmpty()
                .takeIf { it.isNotEmpty() }
            return
        }
        intent?.getStringExtra("fontscale")?.let {
            // -e fontscale 1.4 → persist+apply; non-numeric ("reset") → back to 1.0.
            WearApp.Graph.devSetFontScale(it)
            return
        }
        if (intent?.hasExtra("viewer_demo") == true) {
            // -e viewer_demo → open at fit; `-e viewer_demo 0.5` opens BELOW fit (sub-fit proof);
            // 0/false closes.
            val arg = intent.getStringExtra("viewer_demo").orEmpty()
            WearApp.Graph.devViewerScale.value = when {
                arg.isBlank() || arg == "true" -> 1f
                arg.equals("false", true) || arg == "0" -> null
                else -> arg.toFloatOrNull() ?: 1f
            }
            return
        }
        if (intent?.hasExtra("open_editor") == true) {
            // Prefilled prompt editor + system keyboard (owner batch 2 verification hook; now
            // also the drag-up-orb proof for the hunter era): yes/true/1/'' → default prefill,
            // anything else is the literal draft.
            val v = intent.getStringExtra("open_editor").orEmpty()
            WearApp.Graph.devEditor.value =
                if (v.isBlank() || v.lowercase() in setOf("true", "yes", "1")) "edit me on the watch keyboard"
                else v
            return
        }
        if (intent?.hasExtra("stop_mid_run") == true) {
            val what = intent.getStringExtra("stop_mid_run")
                ?.takeIf { it.isNotBlank() && it != "true" }
                ?: "count slowly from one to thirty, one number per second, no tools"
            WearApp.Graph.devStopMidRun(what)
            return
        }
        if (intent?.hasExtra("approve") == true) { WearApp.Graph.session.approve(); return }
        if (intent?.hasExtra("deny") == true) { WearApp.Graph.session.deny(); return }
        if (intent?.hasExtra("chat_new") == true) {
            val meta = WearApp.Graph.session.createChat()
            Log.i("HermesWear", "chat_new id=${meta.id} session=${meta.sessionTitle}")
            return
        }
        intent?.getStringExtra("open_chat")?.let { WearApp.Graph.session.openChat(it); return }
        if (intent?.hasExtra("chat_dump") == true) { WearApp.Graph.session.dumpChats(); return }
        if (intent?.hasExtra("stt_status") == true && context != null) {
            val model = if (VoskStt.isModelPresent(context)) "present" else "missing"
            Log.i("HermesWear", "stt model=$model tts=${WearApp.Graph.speak.state.value}")
            return
        }
        intent?.getStringExtra("speak")?.let { WearApp.Graph.speak.speakFinal(it); return }
        intent?.getStringExtra("play_url")?.let { MediaPlayerHolder.play(context!!, it); return }
        val text = intent?.getStringExtra("text") ?: return
        WearApp.Graph.session.ask(text)
    }
}
