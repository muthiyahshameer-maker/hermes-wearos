package dev.hermes.wearos.domain

import android.content.Context
import android.util.Log
import dev.hermes.wearos.WearApp
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.data.ChatMeta
import dev.hermes.wearos.data.ChatStore
import dev.hermes.wearos.net.GatewaySessions
import dev.hermes.wearos.net.HermesApi
import dev.hermes.wearos.net.Watchkit
import dev.hermes.wearos.net.HermesApi.Companion.choiceNames
import dev.hermes.wearos.net.HermesApi.Companion.parse
import dev.hermes.wearos.net.HermesApi.Companion.str
import dev.hermes.wearos.notify.Notifs
import dev.hermes.wearos.notify.RunWakeLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ChatMessage(val id: Long, val role: Role, val text: String) {
    enum class Role { User, Hermes, System }
}

data class ApprovalInfo(val runId: String, val requestId: String?, val question: String, val choices: List<String>)

/** Sessions-screen status: cache-served fallback + staleness hint (owner ask #2). */
data class SessionsMeta(val stale: Boolean = false, val fetchedAtMs: Long = 0L, val error: String = "")

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val live: String = "",            // streaming buffer for the answer in flight
    val status: String = "",          // one-line "what the agent is doing"
    val approval: ApprovalInfo? = null,
    val busy: Boolean = false,
    val gen: Long = 0L,               // bumped on chat switch: transcript reloads must not re-speak
    val activeChatId: String = "",
    val activeChatLabel: String = "",
)

/**
 * Multi-chat (SPEC §3): each watch chat = one Hermes session (`sessionTitle` from the local
 * ChatStore registry); per-chat transcript files give offline restore; `active_chat` is the
 * "last session" the watch reopens on launch. Durable idempotency per ask, SSE while
 * foreground, status polling as the fallback/resume path (run status carries terminal output,
 * so a dropped stream or an app restart never loses an answer). See docs/SPEC.md §1, §3, F12.
 */
class ChatSession(
    val settings: AppSettings,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val app: Context,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    companion object { private const val TAG = "HermesWear" }

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)   // events stream: server keepalive is every 10 s
        .build()

    private var askJob: Job? = null
    private var eventSource: EventSource? = null
    private var messageLog = mutableListOf<ChatMessage>()
    private var idCounter = 0L
    private var currentRunId = ""
    private var lastFinishedRun: String? = null
    private var lastApprovalNotified: String? = null // request_id already notified (dedups SSE vs poll)
    private var gen = 0L
    private var stoppedRunId = ""   // run the user STOPPED (owner ask #3): late events/polls ignored
    private var deltaCount = 0L     // deltas seen for the live run — stop_mid_run logcat proof

    /** Active gateway agent (owner ask #1): mirror of AppSettings.profile for the UI/bar label. */
    val profile: StateFlow<String> = settings.profile

    /** Gateway session list for the sessions fast-path (owner ask #2). */
    private val _gatewaySessions = MutableStateFlow<List<GatewaySessions.Info>>(emptyList())
    val gatewaySessions: StateFlow<List<GatewaySessions.Info>> = _gatewaySessions
    private val _sessionsMeta = MutableStateFlow(SessionsMeta())
    val sessionsMeta: StateFlow<SessionsMeta> = _sessionsMeta

    /** The chat the UI is showing; registry mirror is store.activeId (settles after async load). */
    private var activeId = ""

    val chats: StateFlow<List<ChatMeta>> = store.chats
    val activeChat: StateFlow<String> = store.activeId

    fun chats(): StateFlow<List<ChatMeta>> = chats

    private fun nextId() = ++idCounter

    private fun addMessage(role: ChatMessage.Role, text: String) {
        messageLog += ChatMessage(nextId(), role, text)
        _state.value = _state.value.copy(messages = messageLog.toList())
    }

    private suspend fun api(): HermesApi = HermesApi(settings.baseUrl(), settings.token(), http)

    /**
     * Endpoint failover on request failure (SPEC §11): dethrone the endpoint [usedBaseUrl]
     * points at, probe the gateway's remaining endpoints, return the NEW base (with /p/agent)
     * or null when nothing better answered. Fail-soft: no resolver yet / probe storm → null.
     */
    private suspend fun advanceEndpoint(usedBaseUrl: String): String? = try {
        if (WearApp.Graph.hasEndpoints()) WearApp.Graph.endpoints.advanceFrom(usedBaseUrl).takeIf { it.isNotEmpty() }
        else null
    } catch (e: Exception) {
        Log.w(TAG, "endpoint advance check failed: ${e.message}"); null
    }

    /** App start: load registry (with legacy migration), restore the active chat offline,
     *  then pick up a run that was in flight when we died (F10/F12/F13-lite).
     *
     *  OWNER BATCH 2 (last-agent memory): loadProfile() FIRST rebuilds the base URL's `/p/`
     *  segment from the persisted agent (default `wear`), so a cold start opens as the last
     *  profile used; the CHAT SCOPE follows — if the persisted active chat belongs to a
     *  different agent, reopen the newest chat of the remembered agent instead. */
    fun resume() {
        scope.launch {
            settings.loadProfile() // agent mirror + base-URL rebuild from the persisted agent (ask #4)
            store.loadIfNeeded()
            val agent = settings.profileSync()
            val id = store.activeId.value
            val meta = if (id.isNotEmpty()) store.get(id) else null
            val scoped = meta?.takeIf { it.profile == agent }
            if (scoped == null) {
                // First launch, registry emptied, or active chat belongs to ANOTHER agent:
                // continue the remembered agent's newest chat, else start a fresh one.
                val mine = store.chats.value.filter { it.profile == agent }.maxByOrNull { it.updatedAt }
                if (mine != null) {
                    store.setActive(mine.id)
                    enterChat(mine, store.loadTranscript(mine.id))
                } else {
                    val draft = store.draftMeta(null, settings.profileSync())
                    store.addActive(draft)
                    enterChat(draft, emptyList())
                }
            } else {
                enterChat(scoped, store.loadTranscript(scoped.id))
            }
            resumeRun()
        }
    }

    /** Swap UI state to [meta] with its persisted transcript; cancels any stream we own. */
    private fun enterChat(meta: ChatMeta, transcript: List<ChatMessage>) {
        eventSource?.cancel()
        askJob?.cancel()
        activeId = meta.id
        messageLog = transcript.toMutableList()
        idCounter = transcript.maxOfOrNull { it.id } ?: 0L
        currentRunId = ""
        lastFinishedRun = null
        lastApprovalNotified = null
        gen++
        // Opening a chat acknowledges it: cancel its notifications + attention flag (M5).
        Notifs.cancelChat(app, meta.id, meta.lastRunId)
        RunWakeLock.release("chat switch → ${meta.id}") // re-acquired by resumeRun if a run lives here
        store.update(meta.id) { it.copy(pendingAttention = false) }
        _state.value = ChatUiState(
            messages = messageLog.toList(), gen = gen,
            activeChatId = meta.id, activeChatLabel = meta.label,
        )
    }

    /** Continue chat [id] (chats-list tap / dev `open_chat`): persist current, load theirs. */
    fun openChat(id: String) {
        if (id == activeId) return
        scope.launch {
            store.loadIfNeeded()
            val meta = store.get(id) ?: return@launch
            if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
            store.setActive(id)
            enterChat(meta, store.loadTranscript(id))
            resumeRun()
        }
    }

    /** Fresh chat + fresh gateway session; the old chat lands in history instantly (SPEC §3). */
    fun createChat(label: String? = null): ChatMeta {
        val draft = store.draftMeta(label, settings.profileSync())
        if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
        scope.launch {
            store.loadIfNeeded()
            store.addActive(draft)
            enterChat(draft, emptyList())
        }
        return draft
    }

    fun newChat() = createChat(null)

    fun renameChat(id: String, label: String) {
        store.rename(id, label)
        if (id == activeId) _state.value = _state.value.copy(activeChatLabel = label.trim())
    }

    /** Delete a chat; deleting the active one opens the most-recently-touched survivor (or new). */
    fun deleteChat(id: String) {
        scope.launch {
            store.loadIfNeeded()
            if (store.get(id) == null) return@launch
            if (store.activeId.value == id) {
                val next = store.chats.value.filter { it.id != id }.maxByOrNull { it.updatedAt }
                if (next != null) {
                    store.delete(id)
                    store.setActive(next.id)
                    enterChat(next, store.loadTranscript(next.id))
                    resumeRun()
                } else {
                    store.delete(id)
                    newChat()
                }
            } else {
                store.delete(id)
            }
        }
    }

    /** Dev `chat_dump`. */
    fun dumpChats() {
        scope.launch {
            store.loadIfNeeded()
            Log.i(TAG, "chat_dump active=$activeId registry_n=${store.chats.value.size}")
            store.chats.value.forEach {
                Log.i(TAG, "chat_dump chat id=${it.id} session=${it.sessionTitle} label='${it.label}' " +
                    "lastRun=${it.lastRunId} updated=${it.updatedAt}")
            }
        }
    }

    /** If the active chat still has an unfinished run recorded, re-attach / settle it. */
    private suspend fun resumeRun() {
        val meta = store.get(activeId) ?: return
        val runId = meta.lastRunId
        if (runId.isEmpty()) return
        currentRunId = runId
        try {
            val a = api()
            val snap = a.runStatus(runId)
            when (snap.status) {
                "completed" -> finishWith(snap.output ?: "")
                "failed" -> finishWith("⚠️ run failed", error = true)
                "cancelled" -> finishWith("(cancelled)")
                else -> {
                    _state.value = _state.value.copy(busy = true, status = "still working…")
                    openEventStream(a, runId)
                }
            }
        } catch (e: Exception) { /* gateway unreachable: offline reads still work */ }
    }

    fun ask(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        stoppedRunId = ""
        deltaCount = 0L
        addMessage(ChatMessage.Role.User, trimmed)
        _state.value = _state.value.copy(busy = true, live = "", status = "sending…")
        // W8: a chat may consume body tools (MCP) — refresh the watchkit snapshot first
        // (fire-and-forget, rate-limited inside; no-op when all tools are off).
        try { WearApp.Graph.pusher.refreshOnAsk() } catch (e: Exception) { /* Graph not ready */ }
        askJob?.cancel()
        askJob = scope.launch {
            try {
                store.loadIfNeeded()
                if (activeId.isEmpty() || store.get(activeId) == null) {
                    val draft = store.draftMeta(null, settings.profileSync())
                    store.addActive(draft)
                    enterChat(draft, messageLog.toList())
                }
                // Winner freshness (SPEC §11): a no-op while the winner is new (sticky StateFlow),
                // at worst one 2.5 s probe when the 5-min revalidation / primary-backoff is due.
                runCatching { if (WearApp.Graph.hasEndpoints()) WearApp.Graph.endpoints.ensure(false) }
                val a = api()
                val session = store.get(activeId)!!.sessionTitle
                val idem = UUID.randomUUID().toString()
                val accepted = try {
                    a.submitRun(trimmed, session, idem)
                } catch (e: Exception) {
                    // Endpoint failover (SPEC §11): the winner just died mid-session? Advance to
                    // the gateway's next endpoint BEFORE retrying; the Idempotency-Key makes the
                    // retry a replay, never a double-run, whichever endpoint lands the request.
                    Log.w(TAG, "submit failed (${e.message}) — endpoint failover check")
                    val nextBase = advanceEndpoint(a.baseUrl)
                    val retryApi = nextBase?.let { HermesApi(it, settings.token(), http) } ?: run { delay(1_500); a }
                    retryApi.submitRun(trimmed, session, idem)
                }
                store.update(activeId) { it.copy(lastRunId = accepted.runId, updatedAt = System.currentTimeMillis()) }
                settings.setLastRun(accepted.runId) // legacy key: pre-registry resume path (migration source)
                currentRunId = accepted.runId
                Log.i(TAG, "run accepted ${accepted.runId} chat=$activeId session=$session replayed=${accepted.replayed}")
                _state.value = _state.value.copy(status = "thinking…")
                openEventStream(a, accepted.runId)
            } catch (e: Exception) {
                Log.e(TAG, "ask failed", e)
                finishWith("⚠️ ${e.message ?: "network error"}", error = true)
            }
        }
    }

    private fun openEventStream(a: HermesApi, runId: String) {
        eventSource?.cancel()
        RunWakeLock.acquire(app, "run=$runId") // keep doze off our network until settle
        val (hk, hv) = a.authHeader()
        val request = Request.Builder()
            .url(a.eventsUrl(runId))
            .header(hk, hv)
            .header("Accept", "text/event-stream")
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) = handleEvent(runId, data)
            override fun onFailure(es: EventSource, t: Throwable?, response: okhttp3.Response?) {
                // Stream dropped mid-run: fall back to status polling (never lose the answer).
                scope.launch { pollUntilTerminal(a, runId) }
            }
            override fun onClosed(es: EventSource) {
                // Not only terminal events close streams: a run parked in approval (or a proxy
                // timeout) ends the SSE too. If we haven't settled, keep watching via polling —
                // finishWith's exactly-once guard makes this safe alongside a real terminal event.
                if (lastFinishedRun != runId) scope.launch { pollUntilTerminal(a, runId) }
            }
        }
        eventSource = EventSources.createFactory(http).newEventSource(request, listener)
    }

    private fun handleEvent(runId: String, data: String) {
        if (runId != currentRunId || runId == stoppedRunId) return // moved on, or the user hit STOP
        val obj = try { parse(data) } catch (e: Exception) { return }
        when (obj.str("event")) {
            "message.delta" -> obj.str("delta")?.let { d ->
                deltaCount++
                _state.value = _state.value.copy(live = _state.value.live + d, status = "")
            }
            "message.interim" -> if (obj["already_streamed"]?.toString() != "true") obj.str("text")?.let { t ->
                _state.value = _state.value.copy(live = _state.value.live + t)
            }
            "tool.started" -> _state.value = _state.value.copy(status = "🔧 ${obj.str("tool") ?: "tool"}…")
            "tool.completed" -> _state.value = _state.value.copy(status = "")
            "approval.request" -> {
                val q = obj.str("command") ?: obj.str("question") ?: obj.str("message") ?: "Approve this action?"
                onApprovalArrived(ApprovalInfo(runId, obj.str("request_id") ?: obj.str("id"), q, obj.choiceNames()))
            }
            "run.completed" -> finishWith(obj.str("output") ?: _state.value.live)
            "run.failed" -> finishWith("⚠️ ${obj.str("error") ?: obj.str("turn_exit_reason") ?: "run failed"}", error = true)
            "run.cancelled" -> finishWith("(cancelled)")
            // keepalive comments and unknown events: ignored per Hermes docs guidance
        }
    }

    private suspend fun pollUntilTerminal(a: HermesApi, runId: String) {
        Log.i(TAG, "poll fallback attached run=$runId")
        var netLogged = false
        while (runId == currentRunId) {
            val snap = try { a.runStatus(runId) } catch (e: Exception) {
                if (!netLogged) { Log.w(TAG, "status poll degraded run=$runId: ${e.message}"); netLogged = true }
                delay(3_000); continue
            }
            netLogged = false
            when (snap.status) {
                "completed" -> return finishWith(snap.output ?: _state.value.live)
                "failed" -> return finishWith("⚠️ run failed", error = true)
                "cancelled" -> return finishWith("(cancelled)")
                "waiting_for_approval" -> snap.approval?.let { ap ->
                    onApprovalArrived(ApprovalInfo(
                        runId, ap.str("request_id") ?: ap.str("id"),
                        ap.str("command") ?: "Approve?", ap.choiceNames(),
                    ))
                }
            }
            delay(3_000)
        }
    }

    /**
     * Approval now waits on the user (SSE event or status poll). Foreground: the in-app card is
     * enough. Backgrounded: HIGH-priority notification with inline Approve/Deny + attention flag
     * (dedup by request_id — pollUntilTerminal re-reports the same approval every 3 s).
     */
    private fun onApprovalArrived(info: ApprovalInfo) {
        _state.value = _state.value.copy(approval = info)
        if (WearApp.Graph.isForeground) return
        val key = info.requestId ?: info.runId
        if (lastApprovalNotified == key) return
        lastApprovalNotified = key
        store.update(activeId) { it.copy(pendingAttention = true) }
        Notifs.approval(app, activeId, info)
        Log.i(TAG, "approval notif run=${info.runId} chat=$activeId")
    }

    fun respondApproval(choice: String) {
        val info = _state.value.approval ?: return
        _state.value = _state.value.copy(approval = null, status = "running…")
        lastApprovalNotified = null
        Notifs.cancelApproval(app, info.runId)
        scope.launch {
            try { api().respondApproval(info.runId, choice, info.requestId) }
            catch (e: Exception) { finishWith("⚠️ approval failed: ${e.message}", error = true) }
        }
    }

    /**
     * Approve/Deny tapped on the notification (or sent via adb) for run [runId]:
     * first server choice = affirmative, last = denial. If the approval is already in our UI
     * state we route through [respondApproval]; otherwise (cold process / race) we give the
     * resume path a few seconds to surface it, then fall back to the run-status snapshot —
     * the gateway keeps pending approvals there. [done] releases the receiver's goAsync token.
     */
    fun respondApprovalFromNotification(runId: String, approve: Boolean, done: () -> Unit = {}) {
        scope.launch {
            try {
                if (_state.value.approval?.runId != runId) {
                    withTimeoutOrNull(6_000) {
                        while (_state.value.approval?.runId != runId) delay(250)
                    }
                }
                val info = _state.value.approval?.takeIf { it.runId == runId }
                if (info != null) {
                    respondApproval(if (approve) info.choices.first() else info.choices.last())
                } else {
                    val ap = try { api().runStatus(runId).approval } catch (e: Exception) {
                        Log.w(TAG, "approval fallback status failed: ${e.message}"); null
                    }
                    val names = ap?.choiceNames() ?: listOf("approve", "deny")
                    api().respondApproval(runId, if (approve) names.first() else names.last(),
                        ap?.str("request_id") ?: ap?.str("id"))
                    Log.i(TAG, "approval $runId answered via status fallback approve=$approve")
                }
            } catch (e: Exception) {
                Log.e(TAG, "approval response failed run=$runId", e)
            } finally {
                done()
            }
        }
    }

    /** UI/dev helpers: first server choice = affirmative, last = denial (cards use these). */
    fun approve() = _state.value.approval?.let { respondApproval(it.choices.first()) }
    fun deny() = _state.value.approval?.let { respondApproval(it.choices.last()) }

    /**
     * STOP (owner ask #3): the moment the user taps the orb while a run streams, we detach from the
     * stream (SSE + polling ignored via stoppedRunId/currentRunId), settle the partial answer and
     * mark the transcript `— stopped —`. Then a best-effort SERVER cancel: the route table in
     * gateway/platforms/api_server_runs.py `_http_routes` has `POST /v1/runs/{run_id}/stop`
     * (`run_stop` capability) — called, but never required: client-side stop is the deliverable
     * even when the gateway rejects or is unreachable.
     */
    fun stopActiveRun() {
        if (!_state.value.busy) { Log.i(TAG, "stop ignored: no run in flight"); return }
        val runId = currentRunId
        val live = _state.value.live
        stoppedRunId = runId
        eventSource?.cancel()
        askJob?.cancel()
        if (runId.isNotEmpty()) {
            lastFinishedRun = runId // exactly-once guard: racing terminal event/poll cannot re-settle
            scope.launch {
                try { Log.i(TAG, "stop: gateway cancel run=$runId resp=${api().stopRun(runId)}") }
                catch (e: Exception) { Log.w(TAG, "stop: gateway cancel failed, client-side stop stands: ${e.message}") }
            }
        }
        currentRunId = ""
        RunWakeLock.release("stopped run=$runId")
        if (live.isNotBlank()) addMessage(ChatMessage.Role.Hermes, live)
        addMessage(ChatMessage.Role.System, "— stopped —")
        _state.value = _state.value.copy(live = "", status = "", busy = false, approval = null)
        Log.i(TAG, "stop: run=$runId deltas=$deltaCount stream ceased")
        val chatId = activeId
        if (chatId.isNotEmpty()) {
            store.update(chatId) { it.copy(lastRunId = "", updatedAt = System.currentTimeMillis()) }
            scope.launch { settings.setLastRun("") }
            store.saveTranscript(chatId, messageLog)
        }
    }

    /**
     * AGENT SWITCH (owner ask #1): swap the `/p/<profile>` segment of the base URL (everything up
     * to `/p/` stays), adopt the `<profile>-watch-` session prefix for new chats, then show that
     * agent's chat list — newest surviving chat or a fresh one. Invalid name → clean error, no
     * hardcoding, old base stands. [onDone] drives the toast/label.
     */
    fun switchAgent(agent: String, onDone: (ok: Boolean, base: String, error: String) -> Unit = { _, _, _ -> }) {
        scope.launch {
            val old = settings.baseUrl()
            val newBase = ProfileLogic.swapProfile(old, agent)
            if (newBase == null) {
                Log.w(TAG, "switchAgent: rejected profile name '$agent'")
                onDone(false, old, "bad agent name"); return@launch
            }
            settings.setBaseUrl(newBase) // updates the AppSettings profile mirror too
            eventSource?.cancel(); askJob?.cancel()
            if (_state.value.busy || _state.value.live.isNotEmpty() || _state.value.approval != null)
                _state.value = _state.value.copy(busy = false, live = "", status = "", approval = null)
            store.loadIfNeeded()
            val target = store.chats.value.filter { it.profile == agent }.maxByOrNull { it.updatedAt }
            if (target != null) {
                if (activeId.isNotEmpty() && target.id != activeId) store.saveTranscript(activeId, messageLog)
                store.setActive(target.id)
                enterChat(target, store.loadTranscript(target.id))
                resumeRun()
            } else {
                createChat(null) // new agent gets its own chat with the <profile>-watch- prefix
            }
            Log.i(TAG, "agent switched -> $agent base=$newBase chat=$activeId prefix=${ProfileLogic.sessionPrefix(agent)}")
            onDone(true, newBase, "")
        }
    }

    /**
     * RESUME A GATEWAY SESSION (owner ask #2, SPEC M4): adopting gateway session [sessionId] means
     * the next asks carry it as session_id — the watch continues a desktop-started conversation.
     * [sourceProfile] is the profile the Sessions row came from ("" = the current agent).
     * OWNER BUG FIX: a newly-adopted chat used to open EMPTY — its history lives in the
     * profile's state.db, so we backfill it async via watchkit /session_messages after the
     * chat opens (fail-soft: never block or blank the adoption on the fetch).
     */
    fun resumeGatewaySession(sessionId: String, title: String = "", sourceProfile: String = "") {
        if (sessionId.isBlank()) return
        scope.launch {
            store.loadIfNeeded()
            val existing = store.chats.value.firstOrNull { it.sessionTitle == sessionId }
            if (existing != null) {
                var localSize = -1
                if (existing.id != activeId) {
                    if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
                    store.setActive(existing.id)
                    val tr = store.loadTranscript(existing.id)
                    localSize = tr.size
                    enterChat(existing, tr)
                    resumeRun()
                }
                Log.i(TAG, "gateway session resumed chat=${existing.id} session=$sessionId")
                // The OTHER face of the empty-adopt bug: an older adoption sits in the registry
                // with an EMPTY transcript — reopening it showed blank forever. Seed those too.
                backfillHistory(
                    existing,
                    localTranscriptSize = if (existing.id == activeId) messageLog.size else localSize,
                    profileHint = sourceProfile,
                )
                return@launch
            }
            if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
            val meta = store.draftMetaWith(sessionId, title.ifBlank { null }, settings.profileSync())
            store.addActive(meta)
            enterChat(meta, emptyList())
            Log.i(TAG, "gateway session adopted chat=${meta.id} session=$sessionId")
            backfillHistory(meta, localTranscriptSize = 0, profileHint = sourceProfile)
        }
    }

    /** Max wait per watchkit probe; the daemon answers in tens of ms — this is a leash, not a budget. */
    private val historyFetchTimeoutMs = 10_000L

    /**
     * Seed a freshly-adopted chat with its gateway transcript (see SessionHistory for rules).
     * Async + fail-soft: watchkit down/timeout/no rows → the chat simply stays empty with a
     * toast; success prepends caption + history under whatever the user already typed.
     */
    private fun backfillHistory(meta: ChatMeta, localTranscriptSize: Int, profileHint: String) {
        if (!SessionHistory.shouldBackfill(meta.sessionTitle, localTranscriptSize)) return
        scope.launch {
            for (p in SessionHistory.profileProbes(profileHint.ifBlank { meta.profile })) {
                // async on the CHAT scope (not withContext children — those would have to finish
                // before the timeout could return): the await below is what the leash controls.
                val probe = scope.async(Dispatchers.IO) {
                    SessionHistory.parse(Watchkit.fetchSessionMessagesJson(p, meta.sessionTitle))
                }
                val msgs = try {
                    withTimeoutOrNull(historyFetchTimeoutMs) { probe.await() }
                } catch (e: Exception) {
                    Log.i(TAG, "history probe profile=$p failed: ${e.message}"); null
                }
                probe.cancel()
                if (msgs.isNullOrEmpty()) continue
                val stamped = ArrayList<ChatMessage>(msgs.size + 1)
                stamped += ChatMessage(nextId(), ChatMessage.Role.System, SessionHistory.captionLine(msgs.size, p))
                msgs.forEach { stamped += ChatMessage(nextId(), it.role, it.text) }
                if (activeId == meta.id) {
                    stamped += messageLog // messages the user sent during the fetch stay newest/bottom
                    messageLog = stamped
                    _state.value = _state.value.copy(messages = messageLog.toList())
                    store.saveTranscript(meta.id, messageLog)
                } else if (store.loadTranscript(meta.id).isEmpty()) {
                    store.saveTranscript(meta.id, stamped) // navigated away mid-fetch: next open has it
                }
                Log.i(TAG, "history backfill n=${msgs.size} chat=${meta.id} session=${meta.sessionTitle} profile=$p")
                return@launch
            }
            Log.i(TAG, "history backfill unavailable chat=${meta.id} session=${meta.sessionTitle}")
            WearApp.Graph.runOnUi {
                android.widget.Toast.makeText(app, "history unavailable — continuing live", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Gateway sessions fetch for the fast-path screen (owner ask #2, cross-profile upgrade):
     *  1. CURRENT agent's list — `GET {base}/api/sessions` (gateway, wear-token auth) as before;
     *     success caches, failure serves the cache with a stale hint.
     *  2. `default` = the DESKTOP/computer profile — `GET {watchkit}/sessions?profile=default`
     *     (watchkit runs beside the stores, reads state.db directly; see server/watchkit). Its
     *     failure is silent: the screen just shows this agent's chats, exactly like before.
     * Merged newest-first, capped to 30 (owner: "don't cache too many, gets out of hand"), NO
     * transcripts cached — tapping a foreign-profile row adopts it (agent swap + session resume).
     */
    fun refreshGatewaySessions() {
        scope.launch {
            val current = settings.profileSync()
            val (mine, error) = try {
                GatewaySessions.parseProfiled(api().sessionsJson(), current) to ""
            } catch (e: Exception) {
                // Offline/gateway down: serve the merged cache (already profile-tagged rows).
                val (text, _) = settings.sessionsCache()
                val cached = if (text.isNotEmpty()) try { GatewaySessions.parse(text) }
                    catch (e2: Exception) { Log.w(TAG, "sessions cache unreadable: ${e2.message}"); emptyList() }
                else emptyList()
                Log.w(TAG, "sessions refresh degraded (cache shown): ${e.message}")
                cached to (e.message ?: "unreachable")
            }
            val computer = try {
                GatewaySessions.parseProfiled(
                    Watchkit.fetchSessionsJson(ProfileLogic.PROFILE_COMPUTER), ProfileLogic.PROFILE_COMPUTER,
                )
            } catch (e: Exception) {
                Log.i(TAG, "computer sessions skipped (${e.message})")
                emptyList()
            }
            val merged = GatewaySessions.merge(mine + computer)
            _gatewaySessions.value = merged
            val ts = System.currentTimeMillis()
            val stale = error.isNotEmpty()
            _sessionsMeta.value = SessionsMeta(stale = stale, fetchedAtMs = ts, error = error)
            if (!stale) settings.setSessionsCache(GatewaySessions.encode(merged), ts)
        }
    }

    private fun finishWith(text: String, error: Boolean = false) {
        // Exactly-once settle: SSE terminal event, polling fallback, or resume may race.
        if (currentRunId.isNotEmpty() && lastFinishedRun == currentRunId) return
        lastFinishedRun = currentRunId
        Log.i(TAG, "finish run=$currentRunId error=$error len=${text.length}")
        eventSource?.cancel()
        RunWakeLock.release("settle run=$currentRunId")
        if (currentRunId.isNotEmpty()) Notifs.cancelApproval(app, currentRunId)
        addMessage(if (error) ChatMessage.Role.System else ChatMessage.Role.Hermes, text)
        _state.value = _state.value.copy(live = "", status = "", busy = false, approval = null)
        val chatId = activeId
        store.update(chatId) { it.copy(lastRunId = "", updatedAt = System.currentTimeMillis()) }
        scope.launch { settings.setLastRun("") }
        store.saveTranscript(chatId, messageLog)
        // First answer settled: pull the gateway's session title into the list label (§3 titles).
        if (!error && messageLog.count { it.role == ChatMessage.Role.Hermes } == 1) enrichLabel(chatId)
        // M5: settled while backgrounded → watch notification + attention flag (U1).
        if (chatId.isNotEmpty() && !WearApp.Graph.isForeground) {
            store.update(chatId) { it.copy(pendingAttention = true) }
            Notifs.settle(app, chatId, store.get(chatId)?.label ?: "", text, error)
        }
    }

    /**
     * Gateway label sync (verified probe): `GET {base}/api/sessions` → 200
     * `{object:"list", data:[{id,title,...}]}` on Hermes Agent 0.21.7 (also probed: /v1/sessions
     * and /api/sessions/list → 404). Fills an empty label only, so user renames always win.
     */
    private fun enrichLabel(chatId: String) {
        scope.launch {
            val meta = store.get(chatId) ?: return@launch
            if (meta.label.isNotEmpty()) return@launch
            try {
                val s = api().listSessions().firstOrNull { it.id == meta.sessionTitle }
                if (s != null && s.title.isNotEmpty()) {
                    store.update(chatId) { it.copy(label = s.title) }
                    if (chatId == activeId) _state.value = _state.value.copy(activeChatLabel = s.title)
                    Log.i(TAG, "label synced chat=$chatId session=${meta.sessionTitle} title='${s.title}'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "label sync skipped: ${e.message}") // offline/older gateway: label stays session-based
            }
        }
    }
}
