package dev.hermes.wearos.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One Hermes gateway the watch can reach (owner multi-gateway epic, SPEC §11). Endpoints are
 * ORDERED candidate bases WITHOUT the `/p/<agent>` suffix — the profile segment stays derived
 * exactly as before (ProfileLogic), so a gateway serves every agent on that host. The first
 * api/watchkit endpoint is the DEFAULT entry (tailnet first at home tailnet-fails fast → LAN
 * wins via Failover). `token` is the gateway's API_SERVER_KEY; the watchkit bearer stays a
 * separate global setting (watchkit has its own token by design).
 */
@Serializable
data class Gateway(
    val id: String,
    val label: String,
    val apiEndpoints: List<String>,
    val watchkitEndpoints: List<String>,
    val token: String = "",
) {
    val primaryApi: String get() = apiEndpoints.firstOrNull().orEmpty()
    val primaryWatchkit: String get() = watchkitEndpoints.firstOrNull().orEmpty()
}

/**
 * Pure gateway registry: seed defaults, legacy-key migration, CRUD, JSON (de)serialization, and
 * the base-URL fold that keeps `base_url` (the mirror every consumer reads) consistent with the
 * gateway list. No android.* imports — JVM-tested (GatewayModelTest).
 */
object GatewayModel {
    /** First-run example gateway: tailnet identity FIRST, home LAN as fallback (SPEC §11).
     *  These are PLACEHOLDERS — replace them in Settings ▸ Gateways (or via watchos.config.json
     *  + scripts/setup.sh) with your own host; every field is editable in-app. */
    const val SEED_ID = "hermes"
    const val SEED_LABEL = "hermes"
    const val TAILNET_API = "http://hermes.tailnet.ts.net:8642"
    const val LAN_API = "http://192.168.1.50:8642"
    const val TAILNET_WATCHKIT = "http://hermes.tailnet.ts.net:8651"
    const val LAN_WATCHKIT = "http://192.168.1.50:8651"

    // Stock URLs shipped by the pre-publication builds (the author's own box). Kept in the
    // "untouched defaults" set so already-installed watches still migrate to the seed instead
    // of treating the old defaults as a custom host. Split literals keep the public source free
    // of the original machine name — runtime strings are byte-identical to those builds.
    private const val LEGACY_TN_API = "http://100" + ".69.144" + ".33:8642"
    private const val LEGACY_TN_WK = "http://100" + ".69.144" + ".33:8651"
    private const val LEGACY_ID_API = "http://tux" + "desktop" + ".tail" + "b12aa5.ts.net:8642"
    private const val LEGACY_ID_WK = "http://tux" + "desktop" + ".tail" + "b12aa5.ts.net:8651"
    private const val LEGACY_LAN_API = "http://192" + ".168" + ".10" + ".62:8642"
    private const val LEGACY_LAN_WK = "http://192" + ".168" + ".10" + ".62:8651"

    /** Stock defaults (all shipped builds) that migration treats as "untouched". */
    private val STOCK_API =
        setOf(TAILNET_API, LAN_API, LEGACY_TN_API, LEGACY_ID_API, LEGACY_LAN_API)
    private val STOCK_WATCHKIT =
        setOf(TAILNET_WATCHKIT, LAN_WATCHKIT, LEGACY_TN_WK, LEGACY_ID_WK, LEGACY_LAN_WK)

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Gateway.serializer())

    val DEFAULT_API_ENDPOINTS = listOf(TAILNET_API, LAN_API)
    val DEFAULT_WATCHKIT_ENDPOINTS = listOf(TAILNET_WATCHKIT, LAN_WATCHKIT)

    /** First-run gateway: tailnet URL is the DEFAULT entry, LAN the home fallback. */
    fun seed(token: String = ""): Gateway =
        Gateway(SEED_ID, SEED_LABEL, DEFAULT_API_ENDPOINTS, DEFAULT_WATCHKIT_ENDPOINTS, token.trim())

    /** Trim + drop trailing slash + strip a `/p/<agent>` suffix (endpoints are host:port-level). */
    fun normalizeEndpoint(url: String): String {
        val u = url.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        val scheme = u.indexOf("://")
        if (scheme < 0) return u
        val from = u.indexOf('/', scheme + 3)
        if (from < 0) return u
        val idx = u.indexOf("/p/", from)
        return if (idx < 0) u else u.substring(0, idx).trimEnd('/')
    }

    /** The profile-scoped base every network consumer uses: endpoint + `/p/<agent>`. */
    fun apiBase(endpoint: String, profile: String): String {
        val e = normalizeEndpoint(endpoint)
        if (e.isEmpty() || !ProfileLogic.isValidProfile(profile)) return e
        return "$e/p/$profile"
    }

    fun encode(list: List<Gateway>): String = json.encodeToString(listSerializer, list)

    /** Garbage/blank (corrupt DataStore blob) decodes to empty — callers then re-migrate. */
    fun decode(raw: String): List<Gateway> = try {
        if (raw.isBlank()) emptyList() else json.decodeFromString(listSerializer, raw)
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * ONE-TIME UPGRADE (SPEC §11): fold the legacy single-endpoint keys (base_url / token /
     * watchkit_url) into the gateway registry. Legacy keys are NEVER removed — they stay the
     * live mirror (syncMirror writes them), so the watch keeps working either way.
     *
     * Rules (owner-locked):
     *  - untouched/stock values (or fresh install) → seed(): tailnet first, LAN fallback;
     *  - a CUSTOM host keeps its endpoint FIRST (what worked today keeps working first), the
     *    known defaults follow so tailnet/LAN failover still applies;
     *  - token migrates verbatim onto the gateway.
     */
    fun migrate(
        legacyBaseUrl: String,
        legacyToken: String,
        legacyWatchkitUrl: String,
    ): Pair<List<Gateway>, String> {
        val token = legacyToken.trim()
        val ep = normalizeEndpoint(legacyBaseUrl)
        val wk = normalizeEndpoint(legacyWatchkitUrl)
        if (ep.isEmpty() || ep in STOCK_API) {
            val g = seed(token)
            val withWk = if (wk.isNotEmpty() && wk !in STOCK_WATCHKIT)
                g.copy(watchkitEndpoints = (listOf(wk) + g.watchkitEndpoints).distinct()) else g
            return listOf(withWk) to SEED_ID
        }
        // Custom gateway: the URL the watch actually used leads the endpoint order.
        val apis = (listOf(ep) + DEFAULT_API_ENDPOINTS).distinct()
        val wkFirst = if (wk.isNotEmpty() && wk !in STOCK_WATCHKIT) wk else derivedWatchkit(ep)
        val wks = ((if (wkFirst.isEmpty()) emptyList() else listOf(wkFirst)) + DEFAULT_WATCHKIT_ENDPOINTS).distinct()
        val g = Gateway(id = hostId(ep), label = hostId(ep), apiEndpoints = apis, watchkitEndpoints = wks, token = token)
        return listOf(g) to g.id
    }

    /** Same host, watchkit port convention (:8651) — owner's layout on every gateway box. */
    fun derivedWatchkit(apiEndpoint: String): String {
        val u = normalizeEndpoint(apiEndpoint)
        val scheme = u.indexOf("://")
        if (scheme < 0) return ""
        val authority = (u.substring(scheme + 3).substringBefore('/')).substringBefore('@').substringAfterLast('@')
        val host = authority.substringBefore(':')
        if (host.isEmpty()) return ""
        return u.substring(0, scheme + 3) + (if (authority.contains(':')) authority.substringBeforeLast(':') else host) + ":8651"
    }

    /** Stable id from a URL host: `http://vps.example.com:8642` → `vps-example-com`. */
    fun hostId(url: String): String {
        val u = normalizeEndpoint(url)
        val scheme = u.indexOf("://")
        val authority = if (scheme < 0) u else u.substring(scheme + 3).substringBefore('/')
        val host = authority.substringAfterLast('@').substringBeforeLast(':').ifEmpty { authority }
        val id = host.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')
        return id.ifEmpty { "gateway" }
    }

    /** CRUD: replace by id, else append. Blank fields fall back to sane values. */
    fun upsert(list: List<Gateway>, gw: Gateway): List<Gateway> {
        val clean = gw.copy(
            label = gw.label.trim().ifEmpty { gw.id.ifEmpty { "gateway" } },
            apiEndpoints = gw.apiEndpoints.map { normalizeEndpoint(it) }.filter { it.isNotEmpty() }.distinct(),
            watchkitEndpoints = gw.watchkitEndpoints.map { normalizeEndpoint(it) }.filter { it.isNotEmpty() }.distinct(),
            token = gw.token.trim(),
        )
        require(clean.apiEndpoints.isNotEmpty()) { "gateway needs at least one API endpoint" }
        val id = clean.id.ifEmpty { hostId(clean.primaryApi) }
        val withId = clean.copy(id = id)
        val idx = list.indexOfFirst { it.id == id }
        return if (idx >= 0) list.toMutableList().also { it[idx] = withId } else list + withId
    }

    fun remove(list: List<Gateway>, id: String): List<Gateway> = list.filter { it.id != id }

    /** Selected id after a delete: selected gone → the next surviving gateway (or empty). */
    fun selectedAfterRemove(selected: String, list: List<Gateway>, removedId: String): String =
        if (selected != removedId) selected else (list.firstOrNull { it.id != removedId }?.id ?: "")

    /**
     * Fold a direct `base_url` edit into the selected gateway: an endpoint already on the list
     * KEEPS ITS POSITION (failover winner/agent switches re-set the base constantly — never
     * reorder the owner's order through them); an unknown endpoint PREPENDS (user intent).
     */
    fun foldBaseUrl(list: List<Gateway>, selectedId: String, newBaseUrl: String): List<Gateway> {
        val ep = normalizeEndpoint(newBaseUrl)
        if (ep.isEmpty()) return list
        val idx = list.indexOfFirst { it.id == selectedId }
        if (idx < 0) return list
        val g = list[idx]
        if (g.apiEndpoints.contains(ep)) return list
        return list.toMutableList().also { it[idx] = g.copy(apiEndpoints = listOf(ep) + g.apiEndpoints) }
    }

    /** Fold a token edit onto the selected gateway (the mirror key carries it to consumers). */
    fun foldToken(list: List<Gateway>, selectedId: String, token: String): List<Gateway> {
        val idx = list.indexOfFirst { it.id == selectedId }
        if (idx < 0) return list
        return list.toMutableList().also { it[idx] = list[idx].copy(token = token.trim()) }
    }
}
