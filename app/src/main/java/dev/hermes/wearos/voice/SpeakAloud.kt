package dev.hermes.wearos.voice

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.hermes.wearos.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speak-aloud over Android TTS (M2). Answers stream in as SSE deltas, so we sentence-chunk the
 * growing buffer (split on [.!?\n], min chunk 40 chars) and queue each finished sentence once;
 * `finish()` flushes the remainder of the settled answer. Volume is boosted to max on STREAM_MUSIC
 * while a queue is live and restored when it drains.
 *
 * Must be constructed/`init()`ed on the main thread (TTS posts its engine callback to that looper).
 */
class SpeakAloud(context: Context, private val settings: AppSettings, private val scope: CoroutineScope) {

    enum class State { None, Initializing, Ready, MissingEngine }

    companion object {
        private const val TAG = "HermesWear"
        private const val MIN_CHUNK = 40
        private const val SENTENCE_ENDS = ".!?\n"
    }

    private val appContext = context.applicationContext
    private val audio: AudioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(State.None)
    val state: StateFlow<State> = _state

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private var tts: TextToSpeech? = null
    private var utterSeq = 0
    private val pending = AtomicInteger(0)

    /** Saved STREAM_MUSIC volume while a queue is boosted; -1 = not boosted. */
    @Volatile private var savedVolume = -1

    /** Contiguous prefix of the in-flight stream already queued for speech (for finish() flush). */
    private val spokenPrefix = StringBuilder()
    private var scanFrom = 0

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = utteranceSettled()
        @Deprecated("kept for API 30") override fun onError(utteranceId: String?) = utteranceSettled()
        override fun onError(utteranceId: String?, errorCode: Int) = utteranceSettled()
    }

    fun init() {
        _state.value = State.Initializing
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    t.setOnUtteranceProgressListener(progress)
                    val avail = try { t.isLanguageAvailable(Locale.US) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                    if (avail >= TextToSpeech.LANG_AVAILABLE) t.language = Locale.US
                    t.setSpeechRate(1.05f)
                    _state.value = State.Ready
                } else _state.value = State.MissingEngine
            } else {
                _state.value = State.MissingEngine
            }
            Log.i(TAG, "tts init status=$status state=${_state.value}")
        }
    }

    /** Load the persisted master-mute (call from a coroutine). */
    suspend fun loadEnabled() { _enabled.value = settings.voiceOutEnabled() }

    fun setVoiceEnabled(v: Boolean) {
        _enabled.value = v
        if (!v) stop()
        scope.launch { settings.setVoiceOut(v) }
    }

    /**
     * Feed the growing streaming answer (ChatUiState.live). Speaks every completed sentence
     * (>= MIN_CHUNK chars) that hasn't been spoken yet. A shorter-than-consumed buffer means a
     * new run started, so offsets reset.
     */
    fun feed(textSoFar: String) {
        if (!_enabled.value || _state.value != State.Ready) return
        if (textSoFar.length < scanFrom) resetTracking()
        var segStart = scanFrom
        var i = scanFrom
        while (i < textSoFar.length) {
            if (textSoFar[i] in SENTENCE_ENDS && i + 1 - segStart >= MIN_CHUNK) {
                speak(textSoFar.substring(segStart, i + 1), queueAdd = true)
                consumeTo(textSoFar, i + 1)
                segStart = scanFrom
            }
            i++
        }
        // Whitespace between sentences: keep the consumed prefix contiguous, never spoken.
        while (scanFrom < textSoFar.length && textSoFar[scanFrom] == ' ') consumeTo(textSoFar, scanFrom + 1)
    }

    /** Advance the consumed prefix to [to]; prefix always equals stream[0..scanFrom). */
    private fun consumeTo(stream: String, to: Int) {
        spokenPrefix.append(stream, scanFrom, to)
        scanFrom = to
    }

    /** Final settled text arrived: flush whatever the stream never reached. */
    fun finish(fullText: String) {
        if (!_enabled.value || _state.value != State.Ready || fullText.isBlank()) { resetTracking(); return }
        val prefix = spokenPrefix.toString()
        if (fullText.startsWith(prefix)) {
            val rest = fullText.drop(prefix.length).trim()
            if (rest.isNotEmpty()) speak(rest, queueAdd = prefix.isNotEmpty())
        } else {
            // Terminal output diverged from the stream (rare): stop and read the settled answer.
            stop()
            speak(fullText, queueAdd = false)
        }
        resetTracking()
    }

    /** Speak a standalone text (dev `speak` broadcast, notices). */
    fun speakFinal(text: String) {
        if (!_enabled.value || _state.value != State.Ready) return
        stop()
        speak(text, queueAdd = false)
    }

    fun stop() {
        resetTracking()
        try { tts?.stop() } catch (e: Exception) { /* engine gone */ }
        pending.set(0)
        restoreVolume()
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        tts = null
        restoreVolume()
    }

    private fun speak(text: String, queueAdd: Boolean) {
        val t = tts ?: return
        if (_state.value != State.Ready) return
        boostVolume()
        pending.incrementAndGet()
        val mode = if (queueAdd) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        t.speak(text, mode, null, "hermes-${++utterSeq}")
    }

    private fun utteranceSettled() {
        if (pending.decrementAndGet() <= 0) { pending.set(0); restoreVolume() }
    }

    private fun resetTracking() { spokenPrefix.setLength(0); scanFrom = 0 }

    private fun boostVolume() {
        if (savedVolume >= 0) return
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (e: Exception) { savedVolume = -1 }
    }

    private fun restoreVolume() {
        val v = savedVolume
        if (v < 0) return
        savedVolume = -1
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (e: Exception) { }
    }
}
