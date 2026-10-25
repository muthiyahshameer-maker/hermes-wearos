package dev.hermes.wearos.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hermes.wearos.domain.FontScale
import dev.hermes.wearos.domain.Gateway
import dev.hermes.wearos.domain.GatewayModel
import dev.hermes.wearos.domain.ProfileLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Gateway connection settings, Keystore-friendly store later (M2+); DataStore for now. */
class AppSettings(private val context: Context) {
    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_SESSION_ID = stringPreferencesKey("session_id")
        private val KEY_LAST_RUN = stringPreferencesKey("last_run")
        private val KEY_VOICE_OUT = booleanPreferencesKey("voice_out")
        private val KEY_MARKDOWN = booleanPreferencesKey("markdown_rendering")
        private val KEY_CHATS_JSON = stringPreferencesKey("chats_json")
        private val KEY_ACTIVE_CHAT = stringPreferencesKey("active_chat")
        private val KEY_WATCHKIT_URL = stringPreferencesKey("watchkit_url")
        private val KEY_WATCHKIT_TOKEN = stringPreferencesKey("watchkit_token")
        private val KEY_AGENTS_CACHE = stringPreferencesKey("agents_cache_json")
        private val KEY_SESSIONS_CACHE = stringPreferencesKey("gw_sessions_json")
        private val KEY_SESSIONS_TS = longPreferencesKey("gw_sessions_ts")
        // OWNER BATCH 2 last-agent memory (default `wear`; see loadProfile()).
        private val KEY_CURRENT_AGENT = stringPreferencesKey("current_agent")
        // PINCH FONT-SCALE (Layout D epic): persisted text/type multiplier 0.75×–1.6× (FontScale).
        private val KEY_FONT_SCALE = floatPreferencesKey("font_scale")
        // W1-W4 per-tool body switches (SPEC §5 "all OFF until switched" — owner-locked default).
        val BODY_TOOL_KEYS = listOf("watch_heart_rate", "watch_steps", "watch_sleep", "watch_spo2", "watch_location")
        private val KEY_BODY_HR = booleanPreferencesKey("body_watch_heart_rate")
        private val KEY_BODY_STEPS = booleanPreferencesKey("body_watch_steps")
        private val KEY_BODY_SLEEP = booleanPreferencesKey("body_watch_sleep")
        private val KEY_BODY_SPO2 = booleanPreferencesKey("body_watch_spo2")
        private val KEY_BODY_LOC = booleanPreferencesKey("body_watch_location")

        // Example default: tailnet hostname placeholder + wear profile — edit in-app or via
        // scripts/setup.sh; production path is the pairing flow (S5). Mirrors GatewayModel.seed().
        const val DEFAULT_BASE_URL = "http://hermes.tailnet.ts.net:8642/p/wear"
        // watchkit helper daemon (server/watchkit): mermaid render + whisper STT, tailnet-only.
        const val DEFAULT_WATCHKIT_URL = "http://hermes.tailnet.ts.net:8651"

        // OWNER EPIC (multi-gateway, SPEC §11): the registry lives under these NEW keys while
        // the legacy base_url/token/watchkit_url keys above STAY — they are the live MIRROR every
        // consumer reads, rewritten whenever the selected gateway or the winning endpoint
        // changes. Upgrade is additive: an updated watch migrates on first launch and keeps
        // working even if it downgrades.
        private val KEY_GATEWAYS = stringPreferencesKey("gateways_json")
        private val KEY_GW_SELECTED = stringPreferencesKey("gateway_selected")
    }

    suspend fun baseUrl(): String = context.dataStore.data.first()[KEY_BASE_URL] ?: DEFAULT_BASE_URL
    suspend fun token(): String = context.dataStore.data.first()[KEY_TOKEN] ?: ""
    suspend fun sessionId(): String = context.dataStore.data.first()[KEY_SESSION_ID] ?: ""
    suspend fun lastRun(): String = context.dataStore.data.first()[KEY_LAST_RUN] ?: ""

    suspend fun setBaseUrl(v: String) {
        val t = v.trim().trimEnd('/')
        context.dataStore.edit { it[KEY_BASE_URL] = t }
        val seg = ProfileLogic.currentProfile(t)
        if (seg.isNotEmpty()) context.dataStore.edit { it[KEY_CURRENT_AGENT] = seg } // last-agent memory
        _profile.value = seg.ifEmpty { ProfileLogic.FALLBACK_AGENT }
        // Gateway fold (SPEC §11): an endpoint already on the selected gateway keeps its
        // position (failover/agent-switch re-set the base constantly); a NEW host prepends.
        val raw = context.dataStore.data.first()[KEY_GATEWAYS].orEmpty()
        val list = GatewayModel.decode(raw)
        if (list.isNotEmpty()) {
            val sel = context.dataStore.data.first()[KEY_GW_SELECTED].orEmpty()
                .ifEmpty { list.first().id }
            context.dataStore.edit { it[KEY_GATEWAYS] = GatewayModel.encode(GatewayModel.foldBaseUrl(list, sel, t)) }
        }
    }

    /**
     * Active gateway agent, mirrored in memory (owner ask #1): derived from the `/p/<segment>` of
     * the base URL — base-URL swap IS the switch. `profileSync()` feeds synchronous creation paths
     * (new session ids are `<profile>-watch-xxxx`); the StateFlow drives the tiny bar label + list.
     *
     * OWNER BATCH 2 (LAST-AGENT MEMORY): the chosen agent is ALSO persisted (`current_agent`,
     * default `wear`). [loadProfile] runs on cold start and REBUILDS the base URL's `/p/<segment>`
     * FROM the persisted agent, so the app always reopens as the last profile used — crash-safe,
     * it is the DataStore. Every [setBaseUrl] re-persists the URL's segment, so both stay in sync
     * no matter which path changed the agent (dialog, sessions adopt, adb, Settings edit).
     */
    private val _profile = MutableStateFlow(ProfileLogic.FALLBACK_AGENT)
    val profile: StateFlow<String> = _profile
    fun profileSync(): String = _profile.value

    /** Persisted last-used agent (owner batch 2); default `wear`, garbage values fall back. */
    suspend fun savedAgent(): String =
        context.dataStore.data.first()[KEY_CURRENT_AGENT]?.takeIf { ProfileLogic.isValidProfile(it) }
            ?: ProfileLogic.FALLBACK_AGENT

    /** Agent at cold start (or `wear` first open) with the base URL's `/p/` segment rebuilt to it. */
    suspend fun loadProfile(): String {
        val agent = savedAgent()
        val base = baseUrl()
        if (ProfileLogic.currentProfile(base) != agent) {
            val rebuilt = ProfileLogic.baseForAgent(base, agent)
            if (rebuilt != base) {
                context.dataStore.edit { it[KEY_BASE_URL] = rebuilt.trim().trimEnd('/') }
                _profile.value = ProfileLogic.currentProfile(rebuilt).ifEmpty { agent }
                return _profile.value
            }
        }
        _profile.value = ProfileLogic.currentProfile(base).ifEmpty { agent }
        return _profile.value
    }

    /** Agent switcher list cache (watchkit GET /agents, raw `{"agents":[…]}` JSON). */
    suspend fun agentsCache(): String = context.dataStore.data.first()[KEY_AGENTS_CACHE] ?: ""
    suspend fun setAgentsCache(v: String) = context.dataStore.edit { it[KEY_AGENTS_CACHE] = v }.let { }

    /** Gateway `/api/sessions` cache — raw body + fetch time for the offline-readable sessions screen. */
    suspend fun sessionsCache(): Pair<String, Long> = context.dataStore.data.first().let {
        (it[KEY_SESSIONS_CACHE] ?: "") to (it[KEY_SESSIONS_TS] ?: 0L)
    }
    suspend fun setSessionsCache(text: String, ts: Long) = context.dataStore.edit {
        it[KEY_SESSIONS_CACHE] = text; it[KEY_SESSIONS_TS] = ts
    }.let { }

    suspend fun setToken(v: String) {
        context.dataStore.edit { it[KEY_TOKEN] = v.trim() }
        val raw = context.dataStore.data.first()[KEY_GATEWAYS].orEmpty()
        val list = GatewayModel.decode(raw)
        if (list.isNotEmpty()) {
            val sel = context.dataStore.data.first()[KEY_GW_SELECTED].orEmpty().ifEmpty { list.first().id }
            context.dataStore.edit { it[KEY_GATEWAYS] = GatewayModel.encode(GatewayModel.foldToken(list, sel, v)) }
        }
    }
    suspend fun setSessionId(v: String) = context.dataStore.edit { it[KEY_SESSION_ID] = v }.let { }
    suspend fun setLastRun(v: String) = context.dataStore.edit { it[KEY_LAST_RUN] = v }.let { }
    suspend fun clearSession() = context.dataStore.edit { it.remove(KEY_SESSION_ID) }.let { }

    /** Master mute for speak-aloud (M2); default on. */
    suspend fun voiceOutEnabled(): Boolean = context.dataStore.data.first()[KEY_VOICE_OUT] ?: true
    suspend fun setVoiceOut(v: Boolean) = context.dataStore.edit { it[KEY_VOICE_OUT] = v }.let { }

    /**
     * Display ▸ "Markdown rendering" (SPEC §4/§5 F3): renders Hermes replies through
     * ui/md/WatchMarkdown instead of raw text. Default ON; StateFlow so the chat re-renders live.
     */
    private val _markdownEnabled = MutableStateFlow(true)
    val markdownEnabled: StateFlow<Boolean> = _markdownEnabled
    suspend fun markdownRendering(): Boolean = context.dataStore.data.first()[KEY_MARKDOWN] ?: true
    suspend fun loadMarkdownRendering() { _markdownEnabled.value = markdownRendering() }
    suspend fun setMarkdownRendering(v: Boolean) {
        context.dataStore.edit { it[KEY_MARKDOWN] = v }
        _markdownEnabled.value = v
    }

    /**
     * PINCH FONT-SCALE (Layout D epic): transcript TEXT/MARKDOWN multiplier, snapped to the 0.05
     * grid and clamped 0.75×–1.6× by [FontScale]. Pinch anywhere in the message area changes it;
     * double-tap empty transcript resets to 1.0; Settings ▸ Display shows it. StateFlow mirror so
     * the chat re-renders live (same pattern as markdown rendering above).
     */
    private val _fontScale = MutableStateFlow(FontScale.DEFAULT)
    val fontScale: StateFlow<Float> = _fontScale
    suspend fun fontScaleValue(): Float = FontScale.quantize(
        context.dataStore.data.first()[KEY_FONT_SCALE] ?: FontScale.DEFAULT
    )
    suspend fun loadFontScale() { _fontScale.value = fontScaleValue() }
    suspend fun setFontScale(v: Float) {
        val s = FontScale.quantize(v)
        context.dataStore.edit { it[KEY_FONT_SCALE] = s }
        _fontScale.value = s
    }

    /** watchkit helper daemon (server/watchkit): mermaid render + STT fallback; token via Settings/dev hook. */
    suspend fun mermaidBaseUrl(): String = context.dataStore.data.first()[KEY_WATCHKIT_URL] ?: DEFAULT_WATCHKIT_URL
    suspend fun watchkitToken(): String = context.dataStore.data.first()[KEY_WATCHKIT_TOKEN] ?: ""
    suspend fun setMermaidBaseUrl(v: String) = context.dataStore.edit { it[KEY_WATCHKIT_URL] = v.trim().trimEnd('/') }.let { }
    suspend fun setWatchkitToken(v: String) = context.dataStore.edit { it[KEY_WATCHKIT_TOKEN] = v.trim() }.let { }

    // ─── GATEWAY REGISTRY (owner epic, SPEC §11) ───────────────────────────────────────────
    // The registry (gateways_json + gateway_selected) is the source of truth; the legacy keys
    // (base_url/token/watchkit_url) are a MIRROR rewritten by syncMirror() whenever the winner
    // or the selection changes — every existing consumer (ChatSession, Watchkit, pusher, UI)
    // keeps reading the legacy keys and needs zero changes.

    suspend fun gateways(): List<Gateway> = GatewayModel.decode(context.dataStore.data.first()[KEY_GATEWAYS].orEmpty())

    suspend fun saveGateways(list: List<Gateway>) =
        context.dataStore.edit { it[KEY_GATEWAYS] = GatewayModel.encode(list) }.let { }

    suspend fun selectedGatewayId(): String = context.dataStore.data.first().let {
        (it[KEY_GW_SELECTED].orEmpty()).ifEmpty { GatewayModel.decode(it[KEY_GATEWAYS].orEmpty()).firstOrNull()?.id ?: "" }
    }

    suspend fun setSelectedGatewayId(id: String) = context.dataStore.edit { it[KEY_GW_SELECTED] = id }.let { }

    /** The selected gateway (or the first); null when the registry is empty (pre-migration). */
    suspend fun selectedGateway(): Gateway? = gateways().let { list ->
        val sel = context.dataStore.data.first()[KEY_GW_SELECTED].orEmpty()
        list.firstOrNull { it.id == sel } ?: list.firstOrNull()
    }

    /**
     * First-run/upgrade hook: seed or migrate the registry from legacy keys ONCE. Legacy keys
     * stay untouched (still the live mirror); returns the selected gateway.
     */
    suspend fun ensureGateways(): Gateway? {
        val prefs = context.dataStore.data.first()
        val existing = GatewayModel.decode(prefs[KEY_GATEWAYS].orEmpty())
        if (existing.isNotEmpty()) return existing.firstOrNull { it.id == (prefs[KEY_GW_SELECTED].orEmpty()) } ?: existing.first()
        val (list, selected) = GatewayModel.migrate(
            prefs[KEY_BASE_URL] ?: "", prefs[KEY_TOKEN] ?: "", prefs[KEY_WATCHKIT_URL] ?: "",
        )
        context.dataStore.edit {
            it[KEY_GATEWAYS] = GatewayModel.encode(list)
            it[KEY_GW_SELECTED] = selected
        }
        return list.firstOrNull { it.id == selected }
    }

    /** Upsert + (optionally) select. Keeps selection pointer valid. */
    suspend fun saveGateway(gw: Gateway, select: Boolean = false): List<Gateway> {
        val list = GatewayModel.upsert(gateways(), gw)
        context.dataStore.edit {
            it[KEY_GATEWAYS] = GatewayModel.encode(list)
            if (select) it[KEY_GW_SELECTED] = gw.id
        }
        return list
    }

    /** Delete + selection fixup; returns the new selected gateway (null when the list emptied). */
    suspend fun deleteGateway(id: String): Gateway? {
        val list = gateways()
        val next = GatewayModel.remove(list, id)
        val selected = GatewayModel.selectedAfterRemove(selectedGatewayId(), list, id)
        context.dataStore.edit {
            it[KEY_GATEWAYS] = GatewayModel.encode(next)
            it[KEY_GW_SELECTED] = selected
        }
        return next.firstOrNull { it.id == selected }
    }

    /**
     * Rewrite the legacy mirror keys from the winning endpoints (SPEC §11): base_url keeps its
     * `/p/<agent>` segment (profile memory honored), watchkit_url/token follow the gateway.
     * Every consumer of the old keys transparently rides the failover winner.
     */
    suspend fun syncMirror(apiEndpoint: String, watchkitEndpoint: String, token: String? = null) {
        val agent = savedAgent()
        val base = GatewayModel.apiBase(apiEndpoint, agent)
        if (base.isEmpty()) return
        context.dataStore.edit {
            it[KEY_BASE_URL] = base
            if (watchkitEndpoint.isNotEmpty()) it[KEY_WATCHKIT_URL] = watchkitEndpoint
            if (token != null) it[KEY_TOKEN] = token.trim()
        }
    }

    /**
     * W1-W4 body tool switches (Settings ▸ Body tools): tool name → enabled, all default OFF
     * (SPEC §5 "all OFF until switched"). StateFlow mirror for live Settings UI + pusher gate.
     */
    private val _bodyTools = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val bodyTools: StateFlow<Map<String, Boolean>> = _bodyTools
    suspend fun bodyToolEnabled(tool: String): Boolean =
        context.dataStore.data.first()[keyForBody(tool)] ?: false
    suspend fun loadBodyTools() {
        val prefs = context.dataStore.data.first()
        _bodyTools.value = BODY_TOOL_KEYS.associateWith { prefs[keyForBody(it)] ?: false }
    }
    suspend fun setBodyTool(tool: String, v: Boolean) {
        context.dataStore.edit { it[keyForBody(tool)] = v }
        _bodyTools.value = _bodyTools.value + (tool to v)
    }

    private fun keyForBody(tool: String) = when (tool) {
        "watch_heart_rate" -> KEY_BODY_HR
        "watch_steps" -> KEY_BODY_STEPS
        "watch_sleep" -> KEY_BODY_SLEEP
        "watch_spo2" -> KEY_BODY_SPO2
        "watch_location" -> KEY_BODY_LOC
        else -> throw IllegalArgumentException("unknown body tool: $tool")
    }

    /** Chat registry (SPEC §3): whole List<ChatMeta> as one JSON blob — see data/ChatStore.kt. */    suspend fun chatsJson(): String = context.dataStore.data.first()[KEY_CHATS_JSON] ?: ""
    suspend fun setChatsJson(v: String) = context.dataStore.edit { it[KEY_CHATS_JSON] = v }.let { }

    /** Id of the chat the watch opens on launch ("last session" — SPEC §3). */
    suspend fun activeChatId(): String = context.dataStore.data.first()[KEY_ACTIVE_CHAT] ?: ""
    suspend fun setActiveChatId(v: String) = context.dataStore.edit { it[KEY_ACTIVE_CHAT] = v }.let { }

    /** Stable per-install watch session id (one Hermes session until the user starts a new one). */
    suspend fun ensureSessionId(): String {
        val existing = sessionId()
        if (existing.isNotEmpty()) return existing
        val fresh = "wear-watch-" + System.currentTimeMillis().toString(36)
        setSessionId(fresh)
        return fresh
    }
}
