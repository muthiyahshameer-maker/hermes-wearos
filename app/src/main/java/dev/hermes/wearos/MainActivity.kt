package dev.hermes.wearos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.hermes.wearos.ui.AgentPickerDialog
import dev.hermes.wearos.ui.ChatScreen
import dev.hermes.wearos.ui.ChatsScreen
import dev.hermes.wearos.ui.LocalFontScale
import dev.hermes.wearos.ui.PromptActionSheet
import dev.hermes.wearos.ui.PromptEditorOverlay
import dev.hermes.wearos.ui.PttOverlay
import dev.hermes.wearos.ui.SessionsScreen
import dev.hermes.wearos.ui.SettingsScreen
import dev.hermes.wearos.ui.VoiceHost
import dev.hermes.wearos.ui.md.ZoomableMediaViewer
import dev.hermes.wearos.domain.BottomLayout
import dev.hermes.wearos.domain.FontScale
import dev.hermes.wearos.domain.ProfileLogic
import dev.hermes.wearos.domain.PromptEdit
import dev.hermes.wearos.domain.Vad
import dev.hermes.wearos.net.GatewaySessions
import dev.hermes.wearos.voice.SpeakAloud
import dev.hermes.wearos.voice.TalkOrb
import dev.hermes.wearos.voice.VoskEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Watch-local navigation (SPEC §3/§4): chat ⇄ chats-list ⇄ settings ⇄ gateway-sessions. No nav library. */
enum class Screen { Chat, Chats, Settings, Sessions }

/** Deep-link targets (M5): notification tap (open_chat), tile tap (talk), adb parity. */
private data class DeepLink(val openChat: String? = null, val talk: Boolean = false, val nonce: Long = 0L)

class MainActivity : ComponentActivity() {
    private companion object { const val TAG = "HermesWear" }

    private var navNonce = 0L
    private val deepLink = MutableStateFlow<DeepLink?>(null)

    /** M5 U1: POST_NOTIFICATIONS ask on first launch (API 33+). Denied is fine: app still works. */
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Log.w(TAG, "notifs denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        parseDeepLink(intent)
        setContent {
            MaterialTheme {
                val session = WearApp.Graph.session
                val speak = WearApp.Graph.speak
                val settings = WearApp.Graph.settings
                val agents = WearApp.Graph.agents
                val state by session.state.collectAsState()
                val chats by session.chats().collectAsState()
                val activeChatId by session.activeChat.collectAsState()
                val ttsState by speak.state.collectAsState()
                val voiceOut by speak.enabled.collectAsState()
                val markdownOn by settings.markdownEnabled.collectAsState()
                val profile by settings.profile.collectAsState()
                val agentList by agents.agents.collectAsState()
                val agentsRefreshing by agents.refreshing.collectAsState()
                val gatewaySessions by session.gatewaySessions.collectAsState()
                val sessionsMeta by session.sessionsMeta.collectAsState()
                val devPtt by WearApp.Graph.devPtt.collectAsState()
                val devEditor by WearApp.Graph.devEditor.collectAsState()
                val gatewayLabel by WearApp.Graph.gatewayLabel.collectAsState()

                var screen by remember { mutableStateOf(Screen.Chat) }
                var composing by remember { mutableStateOf(false) }
                var draft by remember { mutableStateOf("") }
                var showAgents by remember { mutableStateOf(false) }
                var sessionFilter by remember { mutableStateOf(GatewaySessions.FILTER_ALL) }
                // OWNER BATCH 2 (asks #5/#6): long-pressed prompt → sheet → full-screen editor.
                var promptSheet by remember { mutableStateOf<dev.hermes.wearos.domain.ChatMessage?>(null) }
                var editor by remember { mutableStateOf<EditorReq?>(null) }

                // `-e open_editor "…"` (debug) opens the prompt editor prefilled — the system
                // keyboard proof for owner batch 2 over adb; cleared once consumed.
                LaunchedEffect(devEditor) {
                    devEditor?.let {
                        editor = EditorReq(text = it, original = "", hint = "type or swipe…")
                        WearApp.Graph.devEditor.value = null
                    }
                }

                // --- M2 voice: hold-to-talk + tap hands-free STT state (owner ask #4 / follow-up A) ---
                val context = LocalContext.current
                val uiScope = androidx.compose.runtime.rememberCoroutineScope()
                val listening = remember { mutableStateOf(false) }
                val handsFree = remember { mutableStateOf(false) }
                val vad = remember { mutableStateOf(Vad.start(0L)) }
                val partial = remember { mutableStateOf("") }
                // Readable-overlay support: finalized segments of THIS listening session (oldest
                // first) — PttOverlay stacks these under the live partial (SttFeed).
                val sttHistory = remember { androidx.compose.runtime.snapshots.SnapshotStateList<String>() }
                val sttStatus = remember { mutableStateOf("") }
                val sttJob = remember { mutableStateOf<Job?>(null) }

                fun endListening() { WearApp.Graph.stt.stop() }

                val startListening = remember {
                    { hands: Boolean ->
                        partial.value = ""; sttStatus.value = ""; sttHistory.clear()
                        handsFree.value = hands
                        vad.value = Vad.start(System.currentTimeMillis())
                        listening.value = true
                        sttJob.value = uiScope.launch {
                            WearApp.Graph.stt.start(lifecycleScope).collect { ev ->
                                when (ev) {
                                    is VoskEvent.Partial -> {
                                        partial.value = ev.text
                                        if (handsFree.value) vad.value = Vad.onPartial(vad.value, ev.text, System.currentTimeMillis())
                                    }
                                    is VoskEvent.Final -> {
                                        partial.value = ""
                                        if (ev.text.isNotBlank()) {
                                            sttHistory.add(ev.text) // stays visible while the mic runs on
                                            session.ask(ev.text)
                                        }
                                    }
                                    is VoskEvent.Error -> sttStatus.value = when (ev.message) {
                                        "model-missing" -> "vosk model missing – run get-vosk-model.sh"
                                        "no-permission" -> "mic permission denied"
                                        else -> "stt: ${ev.message}"
                                    }
                                }
                            }
                            listening.value = false
                        }
                    }
                }
                val micGranted = remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    micGranted.value = granted
                    if (granted) startListening(true) else sttStatus.value = "mic permission denied"
                }
                val startListeningGate: (Boolean) -> Unit = { hands ->
                    if (micGranted.value) startListening(hands)
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }

                // Hands-free watchdog (follow-up A): ≥1 word + ~1.2 s silence → finalize & send.
                LaunchedEffect(listening.value, handsFree.value) {
                    if (!listening.value || !handsFree.value) return@LaunchedEffect
                    while (listening.value && handsFree.value) {
                        delay(250)
                        if (Vad.shouldFinalize(vad.value, System.currentTimeMillis())) {
                            Log.i(TAG, "vad: silence ${System.currentTimeMillis() - vad.value.lastWordAtMs}ms after ${vad.value.words} words → send")
                            endListening()
                            break
                        }
                    }
                }

                // M5 deep-links: notification tap opens that chat; "Ask Hermes" tile tap jumps
                // straight into hands-free listening (compose box when the mic isn't granted).
                val link by deepLink.collectAsState()
                LaunchedEffect(link) {
                    val l = link ?: return@LaunchedEffect
                    l.openChat?.let { session.openChat(it); screen = Screen.Chat }
                    if (l.talk) {
                        draft = ""; composing = false; speak.stop()
                        if (micGranted.value) startListening(true) else composing = true
                    }
                }

                // Opening the sessions fast-path refreshes the gateway list (ask #2).
                LaunchedEffect(screen) {
                    if (screen == Screen.Sessions) session.refreshGatewaySessions()
                }

                val voiceStatus = when {
                    sttStatus.value.isNotEmpty() -> sttStatus.value
                    ttsState == SpeakAloud.State.MissingEngine -> "tts: no engine on device"
                    else -> ""
                }

                val openChatAndBack: (String) -> Unit = { id ->
                    session.openChat(id); screen = Screen.Chat
                }

                HermesScaffold {
                    VoiceHost(session, speak)
                    when (screen) {
                        Screen.Chat -> ChatBody(
                            state = state, voiceOut = voiceOut, voiceStatus = voiceStatus,
                            composing = composing, draft = draft,
                            markdown = markdownOn,
                            onDraftChange = { draft = it },
                            onSend = { session.ask(draft); draft = ""; composing = false },
                            listening = listening.value || devPtt != null,
                            pttPartial = partial.value.ifBlank { devPtt ?: "" },
                            pttHistory = if (devPtt != null) emptyList() else sttHistory.toList(),
                            handsFree = handsFree.value,
                            micGranted = micGranted.value,
                            profile = profile,
                            onToggleVoice = { speak.setVoiceEnabled(!voiceOut) },
                            onApprove = { session.respondApproval(it) },
                            onOpenChats = { screen = Screen.Chats },
                            onOpenSessions = { screen = Screen.Sessions },
                            onOpenAgents = {
                                showAgents = true
                                uiScope.launch { agents.refresh() } // dialog shows cache instantly, refreshes live
                            },
                            onKeyboard = {
                                // Overlay chip (batch 2): drop the mic, open the FULL-SCREEN editor —
                                // the system keyboard rides on a real focused field (ask #6).
                                endListening()
                                editor = EditorReq(text = draft, original = "", hint = "type or swipe…")
                            },
                            onEditPrompt = { msg -> promptSheet = msg },
                            editor = editor,
                            onEditorDismiss = { editor = null },
                            onEditorSend = { original, text ->
                                editor = null
                                PromptEdit.rewrap(original, text)?.let {
                                    speak.stop()
                                    session.ask(it) // NEW user turn, same session (see PromptEdit note)
                                }
                            },
                            onOrbTap = {
                                when {
                                    // Tap while listening = finalize & send (empty flush = cancel silently).
                                    listening.value -> endListening()
                                    // Tap while a reply streams = STOP (ask #3).
                                    state.busy -> session.stopActiveRun()
                                    // Compose box open: send (or close when empty).
                                    composing && draft.isNotBlank() -> {
                                        speak.stop()
                                        session.ask(draft); draft = ""; composing = false
                                    }
                                    composing -> composing = false
                                    // Follow-up A: tap starts HANDS-FREE listening (VAD auto-send).
                                    else -> { draft = ""; speak.stop(); startListeningGate(true) }
                                }
                            },
                            onHoldStart = {
                                // Hold-to-talk semantics only when NOT running (ask #3).
                                if (!state.busy && !listening.value) {
                                    draft = ""; composing = false; speak.stop()
                                    if (micGranted.value) startListening(false)
                                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onHoldEnd = { if (!state.busy) endListening() },
                            // PINCH FONT-SCALE: multiplicative 0.05-step apply (persisted). Same-value
                            // events are dropped — a pinch only hits DataStore when a step changes.
                            onFontScalePinch = { factor ->
                                val next = FontScale.apply(settings.fontScale.value, factor)
                                if (next != settings.fontScale.value) uiScope.launch { settings.setFontScale(next) }
                            },
                            onFontScaleReset = { uiScope.launch { settings.setFontScale(FontScale.DEFAULT) } },
                            // DRAG-UP THE ORB = manual GBoard entry (owner ask): the SAME editor the
                            // mic-overlay keyboard chip opens — IME ladder + always-visible send.
                            onOrbDragUp = {
                                endListening()
                                editor = EditorReq(text = draft, original = "", hint = "type or swipe…")
                            },
                        )
                        Screen.Chats -> ExitOnSwipeBack(onBack = { screen = Screen.Chat }) {
                            ChatsScreen(
                                chats = chats.filter { it.profile == profile },
                                agent = profile,
                                activeId = activeChatId,
                                onOpen = openChatAndBack,
                                onCreate = { session.createChat(); screen = Screen.Chat },
                                onRename = { id, label -> session.renameChat(id, label) },
                                onOpenSettings = { screen = Screen.Settings },
                            )
                        }
                        Screen.Sessions -> ExitOnSwipeBack(onBack = { screen = Screen.Chat }) {
                            SessionsScreen(
                                agent = profile,
                                sessions = gatewaySessions,
                                meta = sessionsMeta,
                                filter = sessionFilter,
                                onFilter = { sessionFilter = it },
                                // Pinned NEW SESSION row: fresh chat for the current agent, straight into it.
                                onNewSession = { session.createChat(); screen = Screen.Chat },
                                onOpen = { id, title, prof ->
                                    screen = Screen.Chat
                                    if (prof.isNotEmpty() && prof != profile) {
                                        // ADOPTING A FOREIGN-PROFILE SESSION (desktop chat): the agent must
                                        // swap first — switchAgent's callback lands on a worker thread, so
                                        // every UI touch here goes through Graph.runOnUi (Toast-crash guard).
                                        session.switchAgent(prof) { ok, _, err ->
                                            if (ok) {
                                                session.resumeGatewaySession(id, title, prof)
                                                WearApp.Graph.runOnUi {
                                                    Toast.makeText(context, "agent → $prof", Toast.LENGTH_SHORT).show()
                                                }
                                            } else WearApp.Graph.runOnUi {
                                                Toast.makeText(context, "switch failed: $err", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        session.resumeGatewaySession(id, title)
                                    }
                                },
                                onRefresh = { session.refreshGatewaySessions() },
                            )
                        }
                        Screen.Settings -> ExitOnSwipeBack(onBack = { screen = Screen.Chat }) {
                            SettingsScreen(context = context, settings = settings, speak = speak)
                        }
                    }
                }

                // Dev hook `-e viewer_demo [scale]`: the MEDIA VIEWER over a generated test card —
                // the sub-fit zoom-out proof (d3) over adb without a live gateway image.
                val devViewerScale by WearApp.Graph.devViewerScale.collectAsState()
                devViewerScale?.let { startScale ->
                    ZoomableMediaViewer(
                        bitmap = remember { devMediaTestCard() },
                        caption = "dev card — sub-fit: drag to pan the clipped corners",
                        initialScale = startScale,
                        onDismiss = { WearApp.Graph.devViewerScale.value = null },
                    )
                }

                if (showAgents) {
                    AgentPickerDialog(
                        current = profile,
                        agents = ProfileLogic.displayAgents(agentList, profile),
                        gateway = gatewayLabel,
                        note = when {
                            agentsRefreshing -> "checking gateway…"
                            agentList.isEmpty() -> "offline list · offers wear + default"
                            else -> ""
                        },
                        onPick = { name ->
                            showAgents = false
                            if (name != profile) {
                                session.switchAgent(name) { ok, _, err ->
                                    // REGRESSION (2a32c65 walkthrough): this callback fires from ChatSession's
                                    // Graph scope = Dispatchers.Default; Toast here crashed the app
                                    // ("Can't toast on a thread that has not called Looper.prepare()").
                                    // ALWAYS hop via Graph.runOnUi — see the gate's comment in WearApp.Graph.
                                    WearApp.Graph.runOnUi {
                                        Toast.makeText(
                                            context,
                                            if (ok) "agent → $name" else "switch failed: $err",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                        onDismiss = { showAgents = false },
                    )
                }
                promptSheet?.let { msg ->
                    PromptActionSheet(
                        preview = msg.text,
                        onEdit = {
                            promptSheet = null
                            editor = EditorReq(text = msg.text, original = msg.text, hint = "✎ edit & resend")
                        },
                        onCopy = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("prompt", msg.text))
                            promptSheet = null
                        },
                        onDismiss = { promptSheet = null },
                    )
                }
            }
        }
    }

    /** singleTask relaunch: notification/tile taps land here while we are alive. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseDeepLink(intent)
    }

    /** Foregrounding our chat acknowledges it (M5: cancel notification + attention). */
    override fun onResume() {
        super.onResume()
        WearApp.Graph.clearAttention(WearApp.Graph.session.activeChat.value)
    }

    private fun parseDeepLink(intent: Intent?) {
        val openChat = intent?.getStringExtra(dev.hermes.wearos.notify.Notifs.EXTRA_OPEN_CHAT)
        val talk = if (intent?.hasExtra("talk") == true) try {
            intent.getBooleanExtra("talk", false)
        } catch (e: Exception) { // adb --es talk true arrives as a String
            intent.getStringExtra("talk")?.toBoolean() == true
        } else false
        if (openChat != null || talk) {
            navNonce++
            deepLink.value = DeepLink(openChat, talk, navNonce)
        }
    }
}

/** Right edge-swipe exit (Chats/Sessions/Settings → Chat). Chat itself exits the app via swipe, as before. */
@Composable
private fun ExitOnSwipeBack(onBack: () -> Unit, content: @Composable () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)
    SwipeToDismissBox(
        onDismissed = onBack,
        modifier = Modifier.fillMaxSize(),
    ) { isBackgroundVisible ->
        if (isBackgroundVisible) {
            Text(
                "‹ chats",
                style = MaterialTheme.typography.button,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize().padding(top = 40.dp),
            )
        } else {
            content()
        }
    }
}

@Composable
private fun ChatBody(
    state: dev.hermes.wearos.domain.ChatUiState,
    voiceOut: Boolean,
    voiceStatus: String,
    composing: Boolean,
    draft: String,
    markdown: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    listening: Boolean,
    pttPartial: String,
    pttHistory: List<String>,
    handsFree: Boolean,
    micGranted: Boolean,
    profile: String,
    onToggleVoice: () -> Unit,
    onApprove: (String) -> Unit,
    onOpenChats: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenAgents: () -> Unit,
    onKeyboard: () -> Unit,
    onEditPrompt: (dev.hermes.wearos.domain.ChatMessage) -> Unit,
    editor: EditorReq?,
    onEditorDismiss: () -> Unit,
    onEditorSend: (original: String, text: String) -> Unit,
    onOrbTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onFontScalePinch: (Float) -> Unit,
    onFontScaleReset: () -> Unit,
    onOrbDragUp: () -> Unit,
) {
    // LAYOUT D (OWNER-APPROVED mockup varD.png): the chat fills the round screen and the bottom
    // bar is no longer a Row — it is the computed FOUR-CIRCLE CLUSTER (domain/BottomLayout):
    // talk orb grounded off the bottom edge, AGENT LEFT / SESSIONS RIGHT with labels 8–10px ABOVE
    // them, and the MUTE satellite riding the right arc (THE single mute affordance — the old
    // below-orb pill is gone). While the mic owns the screen (hold OR hands-free tap, ask #4) the
    // transcript is REPLACED by the full-screen live overlay — STT text never starts at the top.
    //
    // NO hardcoded px in the UI: the Box is measured once (onSizeChanged), BottomLayout returns
    // centers+radii in real pixels, and the composition converts px → dp via LocalDensity.
    //
    // The list state is HOISTED here (remembered at ChatBody level, not inside ChatScreen): the
    // PTT overlay and the transcript are an if/else pair, so ChatScreen LEAVES composition while
    // listening. A per-call remember would reset the scroll to the top when the overlay dismisses
    // — the walkthrough's "after stt talk button done, it scrolls to the top of the convo".
    val chatListState = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val vpW = viewport.width.toFloat()
    val vpH = viewport.height.toFloat()
    val cluster = remember(viewport) { BottomLayout.geometry(vpW, vpH) }
    val clusterPadPx = remember(viewport) { BottomLayout.transcriptBottomPad(vpW, vpH) }
    val hasViewport = viewport.width > 0 && viewport.height > 0
    // Transcript/overlay clearance = the cluster's top edge; pre-measure first frame keeps the
    // old 74–78 dp bar clearance.
    val barPadDp = if (hasViewport) with(density) { clusterPadPx.toDp() } else 78.dp
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        if (listening) {
            PttOverlay(
                partial = pttPartial,
                handsFree = handsFree,
                onKeyboard = onKeyboard,
                history = pttHistory,
                modifier = Modifier.padding(bottom = barPadDp),
            )
        } else {
            androidx.compose.foundation.layout.Column(
                // LAYOUT D: the transcript runs the FULL height — the SLC contentPadding (bottom =
                // cluster top) is what keeps the last message clear of the cluster, so ChatAnchor
                // keeps working unchanged. PINCH FONT-SCALE lives on this container: two fingers
                // scale the markdown typography; a single-finger drag is NEVER consumed here, so
                // the SLC keeps scrolling exactly as before (raw-gesture pattern from MediaViewer).
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } >= 2) {
                                    val zoom = ev.calculateZoom()
                                    if (zoom != 1f) {
                                        android.util.Log.d("HermesWear", "pinch font-scale z=$zoom")
                                        onFontScalePinch(zoom)
                                        ev.changes.forEach { if (it.pressed) it.consume() }
                                    }
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    }
                    // HIDDEN RESET: double-tap an empty stretch of the transcript → 1.0× (documented
                    // in SPEC §4; no toast, the type just snaps back and re-anchors). RAW detector —
                    // detectTapGestures bails when a child consumes, and the SLC taps-to-center
                    // consumes every tap inside the transcript; this loop ignores consumption.
                    .rawDoubleTap {
                        android.util.Log.d("HermesWear", "transcript double-tap → font-scale reset")
                        onFontScaleReset()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (composing) {
                    // IME RULE (owner epic): no field depends on Enter. singleLine + Send action
                    // AND an explicit SEND button — a misbehaving IME that turns Enter into a
                    // space can never strand the draft again.
                    // Round discipline: the plain Column above the chat needs its own top inset
                    // (the clock zone), otherwise the field rides under TimeText on a round face.
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = dev.hermes.wearos.ui.roundInsets().topDp.dp + 4.dp)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) onSend() }),
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onBackground),
                            cursorBrush = SolidColor(MaterialTheme.colors.primary),
                        )
                        Text(
                            "send ➤",
                            style = MaterialTheme.typography.caption1,
                            color = if (draft.isNotBlank()) MaterialTheme.colors.primary
                                else MaterialTheme.colors.onBackground.copy(alpha = 0.35f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MaterialTheme.colors.surface.copy(alpha = 0.45f))
                                .clickable(onClickLabel = "send message") {
                                    if (draft.isNotBlank()) onSend()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                ChatScreen(
                    state = state,
                    onApprove = onApprove,
                    voiceEnabled = voiceOut,
                    onToggleVoice = onToggleVoice,
                    voiceStatus = voiceStatus,
                    chatLabel = state.activeChatLabel,
                    onOpenChats = onOpenChats,
                    markdown = markdown,
                    onEditPrompt = onEditPrompt,
                    listState = chatListState,
                    contentPadBottomDp = if (hasViewport) barPadDp else null,
                )
            }
        }
        // LAYOUT D: the bottom cluster (four computed circles + labels) — always on top of the
        // transcript, exactly on the BottomLayout geometry for the measured viewport.
        if (hasViewport) {
            OrbCluster(
                cluster = cluster,
                viewportW = vpW,
                viewportH = vpH,
                profile = profile,
                listening = listening,
                running = state.busy,
                voiceOut = voiceOut,
                onToggleVoice = onToggleVoice,
                onAgent = onOpenAgents,
                onSessions = onOpenSessions,
                onTap = onOrbTap,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
                onDragUp = onOrbDragUp,
            )
        }
        // Full-screen prompt editor (asks #5/#6) LAST in the Box = topmost: covers chat + orb bar,
        // the window resizes for the system keyboard (adjustResize) so the field stays visible.
        editor?.let { req ->
            PromptEditorOverlay(
                original = req.original,
                hint = req.hint,
                prefill = req.text,
                onDismiss = onEditorDismiss,
                onSend = { text -> onEditorSend(req.original, text) },
            )
        }
    }
}

/** Request to open the full-screen editor: prefill, the text it edits ("" = fresh prompt), hint. */
private data class EditorReq(val text: String, val original: String, val hint: String)

/**
 * LAYOUT D — the bottom cluster (owner-approved varD.png): the TALK orb sits on its approved
 * circle (grounded off the bottom edge, ALL old behavior inside TalkOrb: hold PTT, tap hands-free,
 * red STOP morph + ring, listening pulse), the AGENT/SESSIONS satellites are small round chips ON
 * their circles with caption3 labels 8–10 px above them (dynamic: agent short-name / "gw"), and
 * the MUTE satellite rides the right arc grazing the edge — speaker glyphs, THE single mute toggle
 * (old below-orb pill removed). Geometry comes pre-computed from domain/BottomLayout; this file
 * only converts px → dp. No IconButtons anywhere (wear-compose 1.4.1 has none).
 */
@Composable
private fun OrbCluster(
    cluster: BottomLayout.Cluster,
    viewportW: Float,
    viewportH: Float,
    profile: String,
    listening: Boolean,
    running: Boolean,
    voiceOut: Boolean,
    onToggleVoice: () -> Unit,
    onAgent: () -> Unit,
    onSessions: () -> Unit,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onDragUp: () -> Unit,
) {
    val density = LocalDensity.current
    fun dpOf(spot: BottomLayout.Spot): Dp = with(density) { (spot.r * 2f).toDp() }
    // TALK ORB — the Hermes disc on the big circle (recolored inside TalkOrb: #0D1117 + glyph).
    TalkOrb(
        listening = listening,
        running = running,
        diameter = dpOf(cluster.talk),
        modifier = Modifier.onSpot(cluster.talk),
        onTap = onTap,
        onHoldStart = onHoldStart,
        onHoldEnd = onHoldEnd,
        onDragUp = onDragUp,
    )
    // AGENT (left arc): glyph chip + its dynamic short-name label ABOVE.
    RoundBarChip(glyph = "👤", tag = "agents", size = dpOf(cluster.agent), modifier = Modifier.onSpot(cluster.agent), onClick = onAgent)
    SpotLabel(ProfileLogic.shortLabel(profile), cluster.agent, viewportW, viewportH)
    // SESSIONS (right arc): glyph chip + the short gateway label ABOVE.
    RoundBarChip(glyph = "≡", tag = "gw sessions", size = dpOf(cluster.sessions), modifier = Modifier.onSpot(cluster.sessions), onClick = onSessions)
    SpotLabel("gw", cluster.sessions, viewportW, viewportH)
    // MUTE satellite — the right-arc speaker toggle, same AppSettings-persisted speak-aloud state
    // the header chip drives (the old below-orb pill is GONE: exactly one bottom mute affordance).
    Box(
        modifier = Modifier
            .onSpot(cluster.mute)
            .size(dpOf(cluster.mute))
            .clip(CircleShape)
            .background(
                if (voiceOut) MaterialTheme.colors.surface.copy(alpha = 0.45f)
                else MaterialTheme.colors.surface.copy(alpha = 0.18f)
            )
            .clickable(onClickLabel = if (voiceOut) "mute speak-aloud" else "unmute speak-aloud") { onToggleVoice() },
        contentAlignment = Alignment.Center,
    ) {
        // Glyph reads inside the 24r circle: emoji at caption1, muted state dimmed (reads at a glance).
        Text(
            if (voiceOut) "🔊" else "🔇",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onBackground.copy(alpha = if (voiceOut) 0.95f else 0.5f),
        )
    }
}

/** Offset a child so it EXACTLY covers a BottomLayout circle (top-left = center − r, in px). */
private fun Modifier.onSpot(s: BottomLayout.Spot): Modifier = offset {
    IntOffset((s.cx - s.r).roundToInt(), (s.cy - s.r).roundToInt())
}

/**
 * Consumption-agnostic double-tap (transcript font-scale reset). The SLC tap-to-center consumes
 * single taps inside the transcript, which kills foundation `detectTapGestures` (it aborts on a
 * consumed stream). This raw loop reads only raw up-events + system timings — it never consumes,
 * so scrolling and message clicks are untouched. Two ups, same place (< doubleTapSlop), within
 * doubleTapTimeout → onDoubleTap.
 */
private fun Modifier.rawDoubleTap(onDoubleTap: () -> Unit): Modifier = pointerInput(Unit) {
    var lastUpTime = 0L
    var lastUpX = Float.NaN
    var lastUpY = Float.NaN
    val dtMs = viewConfiguration.doubleTapTimeoutMillis
    val slop = 28.dp.toPx() // generous: reset is a deliberate gesture (PointerInputScope: Density)
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var settled = false
        while (!settled) {
            val ev = awaitPointerEvent()
            val up = ev.changes.firstOrNull { !it.pressed && it.previousPressed }
            if (up != null) {
                settled = true
                val now = System.nanoTime() / 1_000_000L
                val x = up.position.x
                val y = up.position.y
                val dx = x - lastUpX
                val dy = y - lastUpY
                if (lastUpX.isFinite() && now - lastUpTime <= dtMs &&
                    kotlin.math.sqrt(dx * dx + dy * dy) <= slop
                ) {
                    lastUpX = Float.NaN // no triple-tap repeat
                    onDoubleTap()
                } else {
                    lastUpTime = now; lastUpX = x; lastUpY = y
                }
            }
        }
    }
}

/**
 * caption3 label centered on a circle's x, baseline sitting 8–10 ref-px ABOVE the circle top
 * (BottomLayout.labelBottomY). Fixed-height bottom-aligned box → exact bottom placement without
 * measuring the text; long agent names ellipsize like the old bar labels did.
 */
@Composable
private fun SpotLabel(
    text: String,
    spot: BottomLayout.Spot,
    viewportW: Float,
    viewportH: Float,
) {
    val density = LocalDensity.current
    val boxH = 18.dp
    val boxW = with(density) { (spot.r * 2f + 56f).toDp() }
    val labelBottom = BottomLayout.labelBottomY(spot, viewportW, viewportH)
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (spot.cx - with(density) { boxW.toPx() } / 2f).roundToInt(),
                    (labelBottom - with(density) { boxH.toPx() }).roundToInt(),
                )
            }
            .size(width = boxW, height = boxH),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            maxLines = 1,
            // Round-safe: the circle narrows at label height, so long names ellipsize instead of
            // spilling under the round clip ("default" → "defau…").
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** One small round secondary button ON a BottomLayout circle (chip + dynamic glyph, no IconButton). */
@Composable
private fun RoundBarChip(glyph: String, tag: String, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colors.surface.copy(alpha = 0.45f))
            .clickable(onClickLabel = tag, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onBackground)
    }
}

/**
 * Dev proof card for `-e viewer_demo`: 720×720 card — border + corner dots (the round viewport
 * CLIPS these at fit) + asymmetric blocks so a BELOW-FIT zoom + pan is unmistakable in a
 * screenshot. android.graphics only (the Compose ui-graphics `applyCanvas` extension is not in
 * this Android artifact set).
 */
internal fun devMediaTestCard(): ImageBitmap {
    val side = 720
    val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    fun paint(hex: String, stroke: Float = 0f) = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor(hex)
        isAntiAlias = true
        if (stroke > 0f) { style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke }
    }
    canvas.drawColor(android.graphics.Color.parseColor("#0D1117"))
    canvas.drawRect(24f, 448f, 424f, 672f, paint("#E57373")) // big BL block — asymmetric on purpose
    canvas.drawRect(456f, 24f, 696f, 224f, paint("#81C784")) // small TR block
    canvas.drawRect(240f, 240f, 480f, 480f, paint("#64B5F6")) // center block
    canvas.drawRect(5f, 5f, side - 5f, side - 5f, paint("#FFFFFF", 10f)) // the clipped edge border
    listOf(
        Triple(40f, 40f, "#FFD54F"), Triple(680f, 40f, "#BA68C8"),
        Triple(40f, 680f, "#4DD0E1"), Triple(680f, 680f, "#FF8A65"),
    ).forEach { (x, y, c) -> canvas.drawCircle(x, y, 34f, paint(c)) } // corner dots = clip proof
    return bmp.asImageBitmap()
}

@Composable
private fun HermesScaffold(content: @Composable ColumnScope.() -> Unit) {
    // PINCH FONT-SCALE: the persisted transcript type multiplier is PUBLISHED here, once, over
    // every screen (MdText + message styles consume via LocalFontScale). Cards never read it.
    val fontScale by WearApp.Graph.settings.fontScale.collectAsState()
    CompositionLocalProvider(LocalFontScale provides fontScale) {
        Scaffold(
            timeText = { TimeText() },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}
