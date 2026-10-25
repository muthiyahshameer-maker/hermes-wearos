package dev.hermes.wearos.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import dev.hermes.wearos.domain.ChatMessage
import dev.hermes.wearos.domain.ChatSession
import dev.hermes.wearos.voice.SpeakAloud

/**
 * Headless bridge between ChatUiState and SpeakAloud (keeps ChatSession UI/voice-agnostic):
 * progressive sentence speech from state.live, full-answer flush when a Hermes message settles,
 * reset on new chat.
 */
@Composable
fun VoiceHost(session: ChatSession, speak: SpeakAloud) {
    val state by session.state.collectAsState()
    val ttsState by speak.state.collectAsState()
    val lastSpokenId = remember { mutableLongStateOf(-1L) }
    val lastGen = remember { mutableLongStateOf(-1L) }

    LaunchedEffect(Unit) {
        Log.i("HermesWear", "voice host up tts=$ttsState voiceOut=${speak.enabled.value}")
    }
    LaunchedEffect(ttsState) {
        Log.i("HermesWear", "tts state -> $ttsState")
    }
    // Chat switch / offline transcript load: swallow the loaded history (never re-speak it).
    LaunchedEffect(state.gen) {
        if (state.gen != lastGen.longValue) {
            lastGen.longValue = state.gen
            lastSpokenId.longValue = state.messages.lastOrNull { it.role == ChatMessage.Role.Hermes }?.id ?: -1L
            speak.stop()
        }
    }
    // Progressive: speak each completed sentence as the answer streams in.
    LaunchedEffect(state.live, state.busy) {
        if (state.busy && state.live.isNotEmpty()) speak.feed(state.live)
    }
    // Settled: flush the remainder exactly once per Hermes message.
    LaunchedEffect(state.messages) {
        if (state.messages.isEmpty()) {
            speak.stop(); lastSpokenId.longValue = -1L
            return@LaunchedEffect
        }
        val last = state.messages.lastOrNull { it.role == ChatMessage.Role.Hermes }
        if (last != null && last.id != lastSpokenId.longValue) {
            lastSpokenId.longValue = last.id
            speak.finish(last.text)
        }
    }
}
