package dev.hermes.wearos.net

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.domain.ImageDecode
import dev.hermes.wearos.domain.SessionHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

/**
 * watchkit helper daemon client (server/watchkit/watchkit.mjs) — the two heavy services the
 * watch can't run: POST /render/mermaid (PNG) + POST /stt/whisper. Shares HermesApi.DEFAULT
 * OkHttp client (tailnet-only bearer; the daemon caps renders at 60s, reads never time out).
 *
 * Config is a StateFlow: a pushed token/baseUrl (Settings Test, adb `set_watchkit`) makes any
 * already-composed MermaidBlockView re-key its LaunchedEffect and auto-retry its render.
 * Decoded images + raw bytes share ONE byte-aware LruCache (~24 MB, owner batch 2): full-res
 * images land on the watch and are downsampled HERE at decode, never re-encoded server-side.
 */
object Watchkit {
    data class Config(val baseUrl: String, val token: String)

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val _config = MutableStateFlow(Config(AppSettings.DEFAULT_WATCHKIT_URL, ""))
    val config: StateFlow<Config> = _config.asStateFlow()

    fun setConfig(baseUrl: String, token: String) {
        _config.value = Config(baseUrl.trim().trimEnd('/').ifEmpty { AppSettings.DEFAULT_WATCHKIT_URL }, token.trim())
    }

    /**
     * Byte-aware image cache (owner batch 2: full-res to the watch): a few big photos must not
     * OOM a watch, so the budget is BYTES (~24 MB), not entry count. Weighs raw byte arrays by
     * size and bitmaps by their real allocation (allocationByteCount). Entries heavier than the
     * whole budget simply never enter the cache (LruCache semantics) — a huge full-res bitmap
     * lives only as long as the zoom viewer holds it.
     */
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024

    private fun weightOf(value: Any): Int = when (value) {
        is ByteArray -> value.size
        is ImageBitmap -> try {
            val bytes = value.asAndroidBitmap().allocationByteCount.toLong()
            if (bytes > Int.MAX_VALUE) Int.MAX_VALUE else bytes.toInt()
        } catch (e: Exception) { value.width * value.height * 4 }
        else -> 1
    }

    private val cache = object : LruCache<String, Any>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Any): Int = weightOf(value)
    }

    private fun sha256(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun esc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""

    /** Raw render fetch (also used by the dev `render_test` hook). Throws ApiException/IOException. */
    suspend fun renderBytes(code: String, width: Int = 460): ByteArray = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val body = "{\"code\":${esc(code)},\"width\":$width}"
        val req = Request.Builder()
            .url("${cfg.baseUrl}/render/mermaid")
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val bytes = r.body?.bytes()
            if (!r.isSuccessful || bytes == null || bytes.isEmpty())
                throw ApiException(r.code, "render ${r.code}: ${(bytes?.let { String(it) } ?: "").take(160)}")
            bytes
        }
    }

    /** Decoded image for a fence, byte-aware cache keyed sha256(code|width). */
    suspend fun renderBitmap(code: String, width: Int = 460): ImageBitmap {
        val key = sha256("render|$code|$width")
        (cache.get(key) as? ImageBitmap)?.let { return it }
        val bytes = renderBytes(code, width)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("png decode failed (${bytes.size} B)")
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    /**
     * F6 raw image fetch. http(s) targets load DIRECT (public URLs — no watchkit bearer leak);
     * anything else is a gateway-host absolute path routed through watchkit GET /img, which
     * allowlists roots. width > 0 asks the server to scale to WebP (legacy); width <= 0 OMITS
     * the param ⇒ watchkit serves the ORIGINAL bytes (owner batch 2: full res to the watch,
     * scaling happens HERE, at decode time). Throws ApiException/IOException.
     */
    suspend fun fetchImageBytes(target: String, width: Int = 0): ByteArray = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val direct = target.startsWith("http://") || target.startsWith("https://")
        val ub = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("img")
            .addQueryParameter("path", target)
        if (width > 0) ub.addQueryParameter("width", width.toString())
        val url: String = if (direct) target else ub.build().toString()
        val rb = Request.Builder().url(url).get()
        if (!direct) rb.header("Authorization", "Bearer ${cfg.token}") // never leak the bearer to public URLs
        val req = rb.build()
        clientCall(req) { r ->
            val bytes = r.body?.bytes()
            if (!r.isSuccessful || bytes == null || bytes.isEmpty())
                throw ApiException(r.code, "img ${r.code}: ${(bytes?.let { String(it) } ?: "").take(160)}")
            bytes
        }
    }

    /** Full-res bytes for a target, fetched ONCE and shared by card + viewer (byte-weighted). */
    private suspend fun imageBytes(target: String): ByteArray {
        val key = sha256("bytes|img|$target")
        (cache.get(key) as? ByteArray)?.let { return it }
        return fetchImageBytes(target, width = 0).also { cache.put(key, it) }
    }

    /**
     * Card bitmap for an `![alt](target)` line: full-res bytes decoded DOWN to ~[maxPx] via
     * BitmapFactory inSampleSize (owner: "scaling should happen on the watch") — crisp on the
     * card, ~1-2 MB instead of tens of MB held per photo.
     */
    suspend fun fetchImageCard(target: String, maxPx: Int = 512): ImageBitmap {
        val key = sha256("card|img|$target|$maxPx")
        (cache.get(key) as? ImageBitmap)?.let { return it }
        val bytes = imageBytes(target)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.inSampleSize = ImageDecode.sampleSize(opts.outWidth, opts.outHeight, maxPx)
        opts.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IOException("image decode failed (${bytes.size} B)")
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    /** Full bitmap for the pinch-zoom viewer — the whole point of shipping full-res. */
    suspend fun fetchImageFull(target: String): ImageBitmap {
        val key = sha256("full|img|$target")
        (cache.get(key) as? ImageBitmap)?.let { return it }
        val bytes = imageBytes(target)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("image decode failed (${bytes.size} B)")
        return bmp.asImageBitmap().also { cache.put(key, it) } // oversize entries simply don't cache
    }

    /**
     * Agent switcher (owner ask #1): GET /agents → gateway profiles list (["default", ...]).
     * Bearer like every other watchkit endpoint; throws ApiException/IOException on any failure —
     * the caller falls back to the cached/DEFAULT list.
     */
    suspend fun fetchAgentList(): List<String> = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val req = Request.Builder()
            .url("${cfg.baseUrl}/agents")
            .get()
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "agents ${r.code}: ${text.take(80)}")
            GatewaySessions.parseAgents(text)
        }
    }

    /**
     * Cross-profile sessions (owner walkthrough): GET /sessions?profile=<p>&limit=<n> — watchkit
     * reads the profile's state.db directly (see server/watchkit). Body is the gateway
     * `/api/sessions` shape + a per-row `profile`, so GatewaySessions.parse handles it verbatim.
     * Throws ApiException/IOException — the caller degrades to the single-profile list.
     */
    suspend fun fetchSessionsJson(profile: String, limit: Int = GatewaySessions.MAX_ROWS): String = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val url = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("sessions")
            .addQueryParameter("profile", profile)
            .addQueryParameter("limit", limit.coerceIn(1, GatewaySessions.MAX_ROWS).toString())
            .build().toString()
        val req = Request.Builder().url(url).get()
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "sessions ${r.code}: ${text.take(80)}")
            text
        }
    }

    /**
     * History backfill (owner bug: adopted computer sessions opened EMPTY): GET
     * /session_messages?profile=<p>&session_id=<id>&limit=<n≤120> — the profile's state.db
     * transcript, cleaned + capped server-side (see server/watchkit). Raw JSON straight to
     * SessionHistory.parse, which owns the mapping. Throws ApiException/IOException.
     */
    suspend fun fetchSessionMessagesJson(profile: String, sessionId: String, limit: Int = SessionHistory.MAX_MESSAGES): String = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val url = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("session_messages")
            .addQueryParameter("profile", profile)
            .addQueryParameter("session_id", sessionId)
            .addQueryParameter("limit", limit.coerceIn(1, SessionHistory.MAX_MESSAGES).toString())
            .build().toString()
        val req = Request.Builder().url(url).get()
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "session_messages ${r.code}: ${text.take(80)}")
            text
        }
    }

    /** Settings ▸ Watchkit "Test": GET /health — public probe, no token needed. */
    suspend fun health(baseUrl: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/health").get().build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "watchkit health ${r.code}: ${text.take(80)}")
            try {
                val obj = HermesApi.parse(text)
                fun field(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive?)?.content
                "ok · watchkit v${field("version") ?: "?"} · up ${field("uptime_s") ?: "?"}s"
            } catch (e: Exception) { "ok (unparsed)" }
        }
    }

    private inline fun <T> clientCall(req: Request, crossinline block: (okhttp3.Response) -> T): T =
        HermesApi.DEFAULT.newCall(req).execute().use { block(it) }
}
